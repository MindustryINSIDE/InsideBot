package inside.interaction.chatinput.settings;

import discord4j.common.util.Snowflake;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.command.ApplicationCommandOption;
import inside.data.EntityRetriever;
import inside.interaction.ChatInputInteractionEnvironment;
import inside.interaction.PermissionCategory;
import inside.interaction.annotation.ChatInputCommand;
import inside.interaction.annotation.Subcommand;
import inside.interaction.chatinput.InteractionSubcommand;
import inside.service.MessageService;
import inside.util.DurationFormat;
import inside.util.MessageUtil;
import io.r2dbc.postgresql.codec.Interval;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.temporal.TemporalAmount;
import java.util.function.Function;

@ChatInputCommand(name = "activity", description = "Настройки роли активного пользователя.", permissions = PermissionCategory.ADMIN)
public class ActivityCommand extends ConfigOwnerCommand {

    public ActivityCommand(MessageService messageService, EntityRetriever entityRetriever) {
        super(messageService, entityRetriever);

        addSubcommand(new EnableSubcommand(this));
        addSubcommand(new ActiveRoleSubcommand(this));
        addSubcommand(new MessageThresholdSubcommand(this));
        addSubcommand(new CountingIntervalSubcommand(this));
    }

    @Subcommand(name = "enable", description = "Включить выдачу роли активного пользовать.")
    protected static class EnableSubcommand extends InteractionSubcommand<ActivityCommand> {

        protected EnableSubcommand(ActivityCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новое состояние.")
                    .type(ApplicationCommandOption.Type.BOOLEAN.getValue()));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getActivityConfigById(guildId)
                    .filter(config -> config.countingInterval() != Interval.ZERO &&
                            config.messageThreshold() != -1 && config.roleId() != -1)
                    .switchIfEmpty(messageService.err(env, "Сначала измените настройки активности").then(Mono.never()))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asBoolean))
                            .switchIfEmpty(messageService.text(env, "Выдача роли активного пользователя: **%s**",
                                    config.enabled() ? "включена" : "выключена").then(Mono.never()))
                            .flatMap(state -> messageService.text(env, "Выдача роли активного пользователя: **%s**",
                                    state ? "включена" : "выключена")
                                    .and(owner.entityRetriever.save(config.withEnabled(state)))));
        }
    }

    @Subcommand(name = "active-role", description = "Настроить роль активного пользователя.")
    protected static class ActiveRoleSubcommand extends InteractionSubcommand<ActivityCommand> {

        protected ActiveRoleSubcommand(ActivityCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новая роль активного пользователя.")
                    .type(ApplicationCommandOption.Type.ROLE.getValue()));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getActivityConfigById(guildId)
                    .switchIfEmpty(owner.entityRetriever.createActivityConfig(guildId))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asSnowflake)
                                    .map(Snowflake::asLong))
                            .switchIfEmpty(messageService.text(env, "Текущая роль активного пользователя: **%s**",
                                    config.roleId() == -1 ? "не установлена" : MessageUtil.getRoleMention(config.roleId()))
                                    .then(Mono.never()))
                            .flatMap(roleId -> messageService.text(env, "Новая роль активного пользователя: %s",
                                    MessageUtil.getRoleMention(roleId))
                                    .and(owner.entityRetriever.save(config.withRoleId(roleId)))));
        }
    }

    @Subcommand(name = "message-threshold", description = "Настроить минимальный порог сообщений для выдачи роли.")
    protected static class MessageThresholdSubcommand extends InteractionSubcommand<ActivityCommand> {

        protected MessageThresholdSubcommand(ActivityCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новый минимальный порог для получения роли активного пользователя.")
                    .type(ApplicationCommandOption.Type.INTEGER.getValue())
                    .minValue(0d)
                    .maxValue((double) Integer.MAX_VALUE));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            return owner.entityRetriever.getActivityConfigById(guildId)
                    .switchIfEmpty(owner.entityRetriever.createActivityConfig(guildId))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asLong)
                                    .map(Math::toIntExact))
                            .switchIfEmpty(messageService.text(env, "Текущий порог активности: **%s**", config.messageThreshold() == -1
                                    ? "не установлен" : config.messageThreshold()).then(Mono.never()))
                            .flatMap(l -> messageService.text(env, "Новый порог активности: **%s**", l)
                                    .and(owner.entityRetriever.save(config.withMessageThreshold(l)))));
        }
    }

    @Subcommand(name = "counting-interval", description = "Настроить период подсчёта активности.")
    protected static class CountingIntervalSubcommand extends InteractionSubcommand<ActivityCommand> {

        protected CountingIntervalSubcommand(ActivityCommand owner) {
            super(owner);

            addOption(builder -> builder.name("value")
                    .description("Новый период подсчёта. (в формате 1д 3ч 44мин)")
                    .type(ApplicationCommandOption.Type.STRING.getValue()));
        }

        @Override
        public Publisher<?> execute(ChatInputInteractionEnvironment env) {

            Snowflake guildId = env.event().getInteraction().getGuildId().orElseThrow();

            Function<TemporalAmount, String> formatDuration = DurationFormat.wordBased()::format;

            return owner.entityRetriever.getActivityConfigById(guildId)
                    .switchIfEmpty(owner.entityRetriever.createActivityConfig(guildId))
                    .flatMap(config -> Mono.justOrEmpty(env.getOption("value")
                                    .flatMap(ApplicationCommandInteractionOption::getValue)
                                    .map(ApplicationCommandInteractionOptionValue::asString))
                            .switchIfEmpty(messageService.text(env, "Текущий период подсчёта активности: **%s**",
                                    formatDuration.apply(config.countingInterval())).then(Mono.never()))
                            .flatMap(str -> {
                                Interval inter = MessageUtil.parseInterval(str);
                                if (inter == null) {
                                    return messageService.err(env, "Неправильный формат длительности");
                                }

                                return messageService.text(env, "Новый период подсчёта активности: **%s**",
                                                formatDuration.apply(inter))
                                        .and(owner.entityRetriever.save(config.withCountingInterval(inter)));
                            }));
        }
    }
}
