package inside.interaction.chatinput.settings;

import discord4j.common.util.Snowflake;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.entity.GuildEmoji;
import discord4j.core.object.entity.channel.Channel;
import discord4j.discordjson.json.EmojiData;
import inside.data.EntityRetriever;
import inside.data.entity.StarboardConfig;
import inside.interaction.ChatInputInteractionEnvironment;
import inside.interaction.annotation.ChatInputCommand;
import inside.interaction.annotation.Subcommand;
import inside.interaction.annotation.SubcommandGroup;
import inside.interaction.chatinput.InteractionSubcommand;
import inside.util.MessageUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;

import static inside.interaction.chatinput.settings.ReactionRolesCommand.emojiPattern;
import static reactor.function.TupleUtils.function;

@ChatInputCommand(name = "starboard", description = "Настройки звёздной доски.")
public class StarboardCommand extends ConfigOwnerCommand {

    public StarboardCommand(EntityRetriever entityRetriever) {
        super(entityRetriever);

        addSubcommand(new EnableSubcommand(this));
        addSubcommand(new ThresholdSubcommand(this));
        addSubcommand(new ChannelSubcommand(this));
        addSubcommand(new SelfStarringSubcommand(this));
        addSubcommand(new EmojisSubcommandGroup(entityRetriever));
    }

    @Subcommand(name = "enable", description = "Включить ведение звёздной доски.")
    protected static class EnableSubcommand extends InteractionSubcommand<StarboardCommand> {

        protected EnableSubcommand(StarboardCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новое состояние.")
                    .type(ApplicationCommandOption.Type.BOOLEAN.getValue()));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getStarboardConfigById(guildId)
                    .switchIfEmpty(err(env, "Сначала измените настройки звёздной доски").then(Mono.never()))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asBoolean))
                            .switchIfEmpty(text(env, "Ведение звёздной доски: **{0}**",
                                    config.enabled() ? "включено" : "выключено").then(Mono.never()))
                            .flatMap(state -> text(env, "Ведение звёздной доски: **{0}**",
                                    state ? "включено" : "выключено")
                                    .and(owner.entityRetriever.save(config.withEnabled(state)))));
        }
    }

    @Subcommand(name = "self-starring", description = "Настроить учёт собственной реакции.")
    protected static class SelfStarringSubcommand extends InteractionSubcommand<StarboardCommand> {

        protected SelfStarringSubcommand(StarboardCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новое состояние.")
                    .type(ApplicationCommandOption.Type.BOOLEAN.getValue()));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getStarboardConfigById(guildId)
                    .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asBoolean))
                            .switchIfEmpty(text(env, "Учёт собственной реакции: **{0}**",
                                    config.selfStarring() ? "включен" : "выключен").then(Mono.never()))
                            .flatMap(state -> text(env, "Учёт собственной реакции: **{0}**",
                                    state ? "включен" : "выключен")
                                    .and(owner.entityRetriever.save(config.withSelfStarring(state)))));
        }
    }

    @Subcommand(name = "threshold", description = "Настроить порог реакция для добавления звёздной доски.")
    protected static class ThresholdSubcommand extends InteractionSubcommand<StarboardCommand> {

        protected ThresholdSubcommand(StarboardCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новый порог.")
                    .type(ApplicationCommandOption.Type.INTEGER.getValue())
                    .minValue(0d)
                    .maxValue((double) Integer.MAX_VALUE));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getStarboardConfigById(guildId)
                    .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asLong)
                                    .map(Math::toIntExact))
                            .switchIfEmpty(text(env, "Текущий порог реакций: **{0}**",
                                    config.threshold() == -1 ? "не установлен" : config.threshold()).then(Mono.never()))
                            .flatMap(threshold -> text(env, "Порог реакций обновлен: **{0}**", threshold)
                                    .and(owner.entityRetriever.save(config.withThreshold(threshold)))));
        }
    }

    @Subcommand(name = "channel", description = "Настроить канал для ведения звёздной доски.")
    protected static class ChannelSubcommand extends InteractionSubcommand<StarboardCommand> {

        protected ChannelSubcommand(StarboardCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новый текстовый канал.")
                    .type(ApplicationCommandOption.Type.CHANNEL.getValue())
                    .channelTypes(Channel.Type.GUILD_TEXT.getValue()));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getStarboardConfigById(guildId)
                    .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asSnowflake))
                            .switchIfEmpty(text(env, "Текущий канал для ведения звёздной доски: **{0}**",
                                    config.starboardChannelId() == -1 ? "не установлен" : MessageUtil.getChannelMention(config.starboardChannelId())).then(Mono.never()))
                            .flatMap(channelId -> text(env, "Канал для ведения доски обновлён: **{0}**",
                                    MessageUtil.getChannelMention(channelId))
                                    .and(owner.entityRetriever.save(config.withStarboardChannelId(channelId.asLong())))));
        }
    }

    @SubcommandGroup(name = "emojis", description = "Настроить учитываемые в подсчёте реакции.")
    protected static class EmojisSubcommandGroup extends ConfigOwnerCommand {

        protected EmojisSubcommandGroup(EntityRetriever entityRetriever) {
            super(entityRetriever);

            addSubcommand(new AddSubcommand(this));
            addSubcommand(new RemoveSubcommand(this));
            addSubcommand(new ClearSubcommand(this));
            addSubcommand(new ListSubcommand(this));
        }

        @Subcommand(name = "add", description = "Добавить эмодзи в список.")
        protected static class AddSubcommand extends InteractionSubcommand<EmojisSubcommandGroup> {

            protected AddSubcommand(EmojisSubcommandGroup owner) {
                super(owner);

                addOption(builder -> builder.name("value")
                        .description("Идентификатор/название эмодзи или юникод символ.")
                        .required(true)
                        .type(ApplicationCommandOption.Type.STRING.getValue()));
            }

            @Override
            public Publisher<?> execute(ChatInputInteractionEnvironment env) {

                Snowflake guildId = env.event().getInteraction()
                        .getGuildId().orElseThrow();

                var emojistr = env.getOption("value")
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
                                return err(env, "Неправильный формат эмодзи").then(Mono.empty());
                            }
                            return Mono.just(EmojiData.builder()
                                    .name(emojistr)
                                    .build());
                        }))
                        .next();

                return fetchEmoji.zipWith(owner.entityRetriever.getStarboardConfigById(guildId)
                        .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId)))
                        .map(function((emoji, config) -> {
                            var set = new HashSet<>(config.emojis());
                            boolean add = set.add(emoji);
                            if (!add) {
                                return err(env, "Такая реакция уже находится в списке.");
                            }

                            return text(env, "Реакция успешно добавлена в список: **{0}**", MessageUtil.getEmojiString(emoji))
                                    .and(owner.entityRetriever.save(config.withEmojis(set)));
                        }));
            }
        }

        @Subcommand(name = "remove", description = "Удалить эмодзи из списка.")
        protected static class RemoveSubcommand extends InteractionSubcommand<EmojisSubcommandGroup> {

            protected RemoveSubcommand(EmojisSubcommandGroup owner) {
                super(owner);

                addOption(builder -> builder.name("value")
                        .description("Идентификатор/название эмодзи или юникод символ.")
                        .required(true)
                        .type(ApplicationCommandOption.Type.STRING.getValue()));
            }

            @Override
            public Publisher<?> execute(ChatInputInteractionEnvironment env) {

                Snowflake guildId = env.event().getInteraction()
                        .getGuildId().orElseThrow();

                var emojistr = env.getOption("value")
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
                                return err(env, "Неправильный формат эмодзи").then(Mono.empty());
                            }
                            return Mono.just(EmojiData.builder()
                                    .name(emojistr)
                                    .build());
                        }))
                        .next();

                return fetchEmoji.zipWith(owner.entityRetriever.getStarboardConfigById(guildId)
                                .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId)))
                        .map(function((emoji, config) -> {
                            var set = new HashSet<>(config.emojis());
                            boolean remove = set.remove(emoji);
                            if (!remove) {
                                return err(env, "Такой реакции нет в списке.");
                            }

                            return text(env, "Реакция успешно удалена из списка: **{0}**", MessageUtil.getEmojiString(emoji))
                                    .and(owner.entityRetriever.save(config.withEmojis(set)));
                        }));
            }
        }

        @Subcommand(name = "clear", description = "Отчистить список эмодзи.")
        protected static class ClearSubcommand extends InteractionSubcommand<EmojisSubcommandGroup> {

            protected ClearSubcommand(EmojisSubcommandGroup owner) {
                super(owner);
            }

            @Override
            public Publisher<?> execute(ChatInputInteractionEnvironment env) {

                Snowflake guildId = env.event().getInteraction()
                        .getGuildId().orElseThrow();

                return owner.entityRetriever.getStarboardConfigById(guildId)
                        .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId))
                        .flatMap(config -> text(env, "Список очищен.")
                                .and(owner.entityRetriever.save(config.withEmojis(List.of()))));
            }
        }

        @Subcommand(name = "list", description = "Отобразить список эмодзи.")
        protected static class ListSubcommand extends InteractionSubcommand<EmojisSubcommandGroup> {

            protected ListSubcommand(EmojisSubcommandGroup owner) {
                super(owner);
            }

            @Override
            public Publisher<?> execute(ChatInputInteractionEnvironment env) {

                Snowflake guildId = env.event().getInteraction()
                        .getGuildId().orElseThrow();

                return owner.entityRetriever.getStarboardConfigById(guildId)
                        .switchIfEmpty(owner.entityRetriever.createStarboardConfig(guildId))
                        .flatMap(config -> config.emojis().isEmpty()
                                ? err(env, "Список эмодзи пуст.")
                                : infoTitled(env, "Текущий список эмодзи", formatEmojis(config)));
            }

            private static String formatEmojis(StarboardConfig config){
                StringBuilder builder = new StringBuilder();
                int lastnceil = 0;
                boolean first = true;
                int d = config.threshold();
                for(EmojiData data : config.emojis()){
                    builder.append(lastnceil).append("..").append(lastnceil + d);
                    builder.append(" - ");
                    builder.append(MessageUtil.getEmojiString(data));
                    builder.append('\n');
                    lastnceil += d;
                    if(first){
                        // TODO: customize period
                        d = 5;
                        first = false;
                    }
                }
                return builder.toString();
            }
        }
    }
}
