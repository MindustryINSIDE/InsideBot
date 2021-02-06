package inside.event;

import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.message.*;
import discord4j.core.object.Embed.Field;
import discord4j.core.object.Region;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.*;
import discord4j.core.object.entity.channel.Channel.Type;
import discord4j.core.spec.*;
import inside.Settings;
import inside.common.command.model.base.CommandReference;
import inside.common.command.service.CommandHandler;
import inside.data.entity.*;
import inside.data.service.*;
import inside.event.audit.AuditEventHandler;
import inside.util.*;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.*;
import reactor.util.context.Context;

import java.util.*;
import java.util.function.Consumer;

import static inside.event.audit.AuditEventType.*;
import static inside.util.ContextUtil.*;

@Component
public class MessageEventHandler extends AuditEventHandler{
    private static final Logger log = Loggers.getLogger(MessageEventHandler.class);

    @Autowired
    private AdminService adminService;

    @Autowired
    private CommandHandler commandHandler;

    @Autowired
    private EntityRetriever entityRetriever;

    @Autowired
    private Settings settings;

    @Override
    public Publisher<?> onMessageCreate(MessageCreateEvent event){
        Message message = event.getMessage();
        String text = message.getContent();
        Member member = event.getMember().orElse(null);
        if(member == null || message.getType() != Message.Type.DEFAULT){
            return Mono.empty();
        }

        Mono<TextChannel> channel = message.getChannel().ofType(TextChannel.class);
        User user = message.getAuthor().orElse(null);
        Snowflake guildId = event.getGuildId().orElse(null);
        if(DiscordUtil.isBot(user) || guildId == null){
            return Mono.empty();
        }

        Snowflake userId = user.getId();
        LocalMember localMember = entityRetriever.getMember(member, () -> new LocalMember(member));

        localMember.addToSeq();
        localMember.lastSentMessage(Calendar.getInstance());
        entityRetriever.save(localMember);

        Mono<Void> config = Mono.justOrEmpty(entityRetriever.getGuildById(guildId))
                .switchIfEmpty(event.getGuild().flatMap(Guild::getRegion).flatMap(region -> Mono.fromRunnable(() -> {
                    GuildConfig guildConfig = new GuildConfig(guildId);
                    guildConfig.locale(LocaleUtil.get(region));
                    guildConfig.prefix(settings.prefix);
                    guildConfig.timeZone(TimeZone.getTimeZone("Etc/Greenwich"));
                    entityRetriever.save(guildConfig);
                })))
                .then();

        Mono<?> messageInfo = channel.filter(textChannel -> textChannel.getType() == Type.GUILD_TEXT)
                .filter(__ -> !MessageUtil.isEmpty(message) && !message.isTts() && message.getEmbeds().isEmpty())
                .doOnNext(signal -> {
                    MessageInfo info = new MessageInfo();
                    info.userId(userId);
                    info.messageId(message.getId());
                    info.guildId(guildId);
                    info.timestamp(Calendar.getInstance());
                    info.content(MessageUtil.effectiveContent(message));
                    messageService.save(info);
                });

        context = Context.of(KEY_GUILD_ID, guildId,
                             KEY_LOCALE, entityRetriever.locale(guildId),
                             KEY_TIMEZONE, entityRetriever.timeZone(guildId));

        CommandReference reference = CommandReference.builder()
                .event(event)
                .context(context)
                .localMember(localMember)
                .channel(() -> channel)
                .build();

        Mono<Void> handle = adminService.isAdmin(member) ? commandHandler.handleMessage(text, reference).contextWrite(context) : Mono.empty();

        return messageInfo.flatMap(__ -> Mono.when(config, handle));
    }

    @Override
    public Publisher<?> onMessageUpdate(MessageUpdateEvent event){
        Message message = event.getMessage().block();
        if(message == null || message.getChannel().map(Channel::getType).block() != Type.GUILD_TEXT){
            return Mono.empty();
        }

        User user = message.getAuthor().orElse(null);
        TextChannel c = message.getChannel().ofType(TextChannel.class).block();
        if(DiscordUtil.isBot(user) || c == null || !messageService.exists(message.getId())){
            return Mono.empty();
        }

        MessageInfo info = messageService.getById(event.getMessageId());
        String oldContent = info.content();
        String newContent = MessageUtil.effectiveContent(message);
        boolean under = newContent.length() >= Field.MAX_VALUE_LENGTH || oldContent.length() >= Field.MAX_VALUE_LENGTH;

        if(message.isPinned() || newContent.equals(oldContent)){
            return Mono.empty();
        }

        Snowflake guildId = info.guildId();
        context = Context.of(KEY_GUILD_ID, guildId,
                             KEY_LOCALE, entityRetriever.locale(guildId),
                             KEY_TIMEZONE, entityRetriever.timeZone(guildId));

        Consumer<EmbedCreateSpec> embed = spec -> {
            spec.setColor(messageEdit.color);
            spec.setAuthor(user.getUsername(), null, user.getAvatarUrl());
            spec.setTitle(messageService.format(context, "audit.message.edit.title", c.getName()));
            spec.setDescription(messageService.format(context, "audit.message.edit.description",
                                                      c.getGuildId().asString(),
                                                      c.getId().asString(),
                                                      message.getId().asString()));

            if(oldContent.length() > 0){
                spec.addField(messageService.get(context, "audit.message.old-content.title"),
                              MessageUtil.substringTo(oldContent, Field.MAX_VALUE_LENGTH), false);
            }

            if(newContent.length() > 0){
                spec.addField(messageService.get(context, "audit.message.new-content.title"),
                              MessageUtil.substringTo(newContent, Field.MAX_VALUE_LENGTH), true);
            }

            spec.setFooter(timestamp(), null);
        };

        MessageCreateSpec spec = new MessageCreateSpec().setEmbed(embed);
        if(under){
            StringInputStream input = new StringInputStream();
            input.writeString(String.format("%s:%n%s%n%n%s:%n%s",
                    messageService.get(context, "audit.message.old-content.title"), oldContent,
                    messageService.get(context, "audit.message.new-content.title"), newContent)
            );
            spec.addFile("message.txt", input);
        }

        return log(c.getGuildId(), spec).contextWrite(context).then(Mono.fromRunnable(() -> {
            info.content(newContent);
            messageService.save(info);
        }));
    }

    @Override
    public Publisher<?> onMessageDelete(MessageDeleteEvent event){
        Message message = event.getMessage().orElse(null);
        if(message == null){
            return Mono.empty();
        }

        User user = message.getAuthor().orElse(null);
        Snowflake guildId = event.getGuildId().orElse(null);
        if(DiscordUtil.isBot(user) || guildId == null){
            return Mono.empty();
        }

        if(!messageService.exists(message.getId()) || messageService.isCleared(message.getId())){
            return Mono.empty();
        }

        MessageInfo info = messageService.getById(message.getId());
        String content = info.content();

        context = Context.of(KEY_GUILD_ID, guildId,
                             KEY_LOCALE, entityRetriever.locale(guildId),
                             KEY_TIMEZONE, entityRetriever.timeZone(guildId));

        Mono<MessageCreateSpec> spec = Mono.zip(event.getGuild(), event.getChannel().ofType(TextChannel.class))
                .map(TupleUtils.function((guild, channel) -> {
                    Consumer<EmbedCreateSpec> embedSpec = embed -> {
                        embed.setColor(messageDelete.color);
                        embed.setAuthor(user.getUsername(), null, user.getAvatarUrl());
                        embed.setTitle(messageService.format(context, "audit.message.delete.title", channel.getName()));
                        embed.setFooter(timestamp(), null);

                        if(content.length() > 0){
                            embed.addField(messageService.get(context, "audit.message.deleted-content.title"),
                                          MessageUtil.substringTo(content, Field.MAX_VALUE_LENGTH), true);
                        }
                    };

                    MessageCreateSpec messageSpec = new MessageCreateSpec().setEmbed(embedSpec);
                    if(content.length() >= Field.MAX_VALUE_LENGTH){
                        StringInputStream input = new StringInputStream();
                        input.writeString(String.format("%s:%n%s", messageService.get(context, "audit.message.deleted-content.title"), content));
                        messageSpec.addFile("message.txt", input);
                    }

                    return messageSpec;
                }));

        return spec.flatMap(messageSpec -> log(guildId, messageSpec).contextWrite(context).then(Mono.fromRunnable(() -> messageService.delete(info))));
    }
}
