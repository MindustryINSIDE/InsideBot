package inside.interaction.chatinput.settings;

import discord4j.common.util.Snowflake;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.command.ApplicationCommandOption.Type;
import discord4j.core.object.entity.GuildEmoji;
import discord4j.discordjson.json.EmojiData;
import inside.data.EntityRetriever;
import inside.data.entity.ReactionRole;
import inside.interaction.ChatInputInteractionEnvironment;
import inside.interaction.PermissionCategory;
import inside.interaction.annotation.ChatInputCommand;
import inside.interaction.annotation.Option;
import inside.interaction.annotation.Subcommand;
import inside.interaction.chatinput.InteractionSubcommand;
import inside.interaction.chatinput.InteractionSubcommandGroup;
import inside.service.MessageService;
import inside.util.MessageUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static inside.data.entity.ReactionRole.MAX_PER_MESSAGE;
import static reactor.bool.BooleanUtils.not;

@ChatInputCommand(value = "reaction-roles", permissions = PermissionCategory.ADMIN)
// = "Настройки реакций-ролей."
public class ReactionRolesCommand extends InteractionSubcommandGroup {

    // (я не особо уверен что это находит только эмодзи)
    public static Pattern emojiPattern = Pattern.compile("^\\p{So}$");

    public ReactionRolesCommand(MessageService messageService, EntityRetriever entityRetriever) {
        super(messageService, entityRetriever);

        addSubcommand(new ListSubcommand(this));
        addSubcommand(new AddSubcommand(this));
        addSubcommand(new RemoveSubcommand(this));
        addSubcommand(new ClearSubcommand(this));
    }

    static String format(ReactionRole e) {
        return String.format("%s -> %s (%s)\n",
                e.messageId(), MessageUtil.getRoleMention(e.roleId()),
                MessageUtil.getEmojiString(e.emoji()));
    }

    @Subcommand("list")// = "Отобразить текущий список реакций-ролей для указанного сообщения.")
    @Option(name = "message-id", required = true, type = Type.STRING)
    protected static class ListSubcommand extends InteractionSubcommand<ReactionRolesCommand> {

        protected ListSubcommand(ReactionRolesCommand owner) {
            super(owner);
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            Snowflake messageId = env.getOption("message-id")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString)
                    .map(MessageUtil::parseId)
                    .orElse(null);

            if (messageId == null) {
                return messageService.err(env, "Неправильный формат ID сообщения.");
            }

            return owner.entityRetriever.getAllReactionRolesById(guildId, messageId)
                    .switchIfEmpty(messageService.err(env, "Список реакций-ролей пуст").then(Mono.never()))
                    .map(ReactionRolesCommand::format)
                    .collect(Collectors.joining())
                    .flatMap(str -> messageService.infoTitled(env, "Список реакций-ролей", str));
        }
    }

    @Subcommand("add")// = "Добавить новую реакцию-роль к сообщению.")
    @Option(name = "emoji", required = true, type = Type.STRING)
    @Option(name = "message-id", required = true, type = Type.STRING) // TODO: лимит на длину
    @Option(name = "role", required = true, type = Type.ROLE)
    protected static class AddSubcommand extends InteractionSubcommand<ReactionRolesCommand> {

        protected AddSubcommand(ReactionRolesCommand owner) {
            super(owner);
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            String emojistr = env.getOption("emoji")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString)
                    .orElseThrow();

            Mono<EmojiData> fetchEmoji = env.event().getClient().getGuildEmojis(guildId)
                    .filter(emoji -> emoji.asFormat().equals(emojistr) ||
                            emoji.getName().equals(emojistr) ||
                            emoji.getId().asString().equals(emojistr))
                    .map(GuildEmoji::getData)
                    .switchIfEmpty(Mono.defer(() -> {
                        Matcher mtch = emojiPattern.matcher(emojistr);
                        if (!mtch.matches()) {
                            return messageService.err(env, "Неправильный формат эмодзи").then(Mono.empty());
                        }
                        return Mono.just(EmojiData.builder()
                                .name(emojistr)
                                .build());
                    }))
                    .next();

            Snowflake roleId = env.getOption("role")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asSnowflake)
                    .orElseThrow();

            Snowflake messageId = env.getOption("message-id")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString)
                    .map(MessageUtil::parseId)
                    .orElse(null);

            if (messageId == null) {
                return messageService.err(env, "Неправильный формат ID сообщения.");
            }

            return fetchEmoji.filterWhen(ignored -> owner.entityRetriever.reactionRolesCountById(guildId, messageId)
                            .map(l -> l < MAX_PER_MESSAGE))
                    .switchIfEmpty(messageService.err(env, "Нельзя создать ещё одну реакцию-роль, " +
                            "так как сообщение уже имеет максимальное количество реакций (**%s**)", MAX_PER_MESSAGE).then(Mono.empty()))
                    .filterWhen(e -> not(owner.entityRetriever.getReactionRoleById(guildId, messageId, roleId).hasElement()))
                    .switchIfEmpty(messageService.err(env, "Такая реакция-роль уже существует").then(Mono.empty()))
                    .map(emoji -> ReactionRole.builder()
                            .guildId(guildId.asLong())
                            .messageId(messageId.asLong())
                            .roleId(roleId.asLong())
                            .emoji(emoji)
                            .build())
                    .flatMap(reactionRole -> messageService.text(env, "Реакция-роль успешно добавлена: %s", format(reactionRole))
                            .and(owner.entityRetriever.save(reactionRole)));
        }
    }

    @Subcommand(value = "remove")// = "Удалить реакцию-роль с сообщения.")
    @Option(name = "message-id", required = true, type = Type.STRING)
    @Option(name = "role", required = true, type = Type.ROLE)
    protected static class RemoveSubcommand extends InteractionSubcommand<ReactionRolesCommand> {

        protected RemoveSubcommand(ReactionRolesCommand owner) {
            super(owner);
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            Snowflake roleId = env.getOption("role")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asSnowflake)
                    .orElseThrow();

            Snowflake messageId = env.getOption("message-id")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString)
                    .map(MessageUtil::parseId)
                    .orElse(null);

            if (messageId == null) {
                return messageService.err(env, "Неправильный формат ID сообщения.");
            }

            return owner.entityRetriever.getReactionRoleById(guildId, messageId, roleId)
                    .switchIfEmpty(messageService.err(env, "Реакция-роль прикрепленная к данному сообщению не найдена").then(Mono.empty()))
                    .flatMap(e -> messageService.text(env, "Реакция-роль успешно удалена: %s", format(e))
                            .and(owner.entityRetriever.delete(e)));
        }
    }

    @Subcommand(value = "clear")// = "Удалить все реакции-роли с сообщения.")
    @Option(name = "message-id", required = true, type = Type.STRING)
    protected static class ClearSubcommand extends InteractionSubcommand<ReactionRolesCommand> {

        protected ClearSubcommand(ReactionRolesCommand owner) {
            super(owner);
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            Snowflake messageId = env.getOption("message-id")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString)
                    .map(MessageUtil::parseId)
                    .orElse(null);

            if (messageId == null) {
                return messageService.err(env, "Неправильный формат ID сообщения.");
            }

            return owner.entityRetriever.reactionRolesCountById(guildId, messageId)
                    .filter(l -> l > 0)
                    .switchIfEmpty(messageService.text(env, "Список реакций-ролей пуст").then(Mono.never()))
                    .flatMap(l -> messageService.text(env, "Список реакций-ролей очищен")
                            .and(owner.entityRetriever.deleteAllReactionRolesById(guildId, messageId)));
        }
    }
}
