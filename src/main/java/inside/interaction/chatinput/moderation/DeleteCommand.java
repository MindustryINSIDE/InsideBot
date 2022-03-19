package inside.interaction.chatinput.moderation;

import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import inside.data.EntityRetriever;
import inside.interaction.ChatInputInteractionEnvironment;
import inside.interaction.PermissionCategory;
import inside.interaction.annotation.ChatInputCommand;
import inside.service.MessageService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;

import java.time.Duration;
import java.time.Instant;

@ChatInputCommand(name = "delete", description = "Удалить указанное число сообщений", permissions = PermissionCategory.MODERATOR)
public class DeleteCommand extends ModerationCommand {

    private static final int MAX_DELETED_MESSAGES = 100;

    public DeleteCommand(MessageService messageService, EntityRetriever entityRetriever) {
        super(messageService, entityRetriever);

        addOption(builder -> builder.name("count")
                .description("Количество сообщений на удаление")
                .type(ApplicationCommandOption.Type.INTEGER.getValue())
                .required(true)
                .minValue(1d)
                .maxValue((double) MAX_DELETED_MESSAGES));
    }

    @Override
    public Publisher<?> execute(ChatInputInteractionEnvironment env) {
        int count = env.getOption("count")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong)
                .map(Math::toIntExact)
                .orElseThrow();

        Instant timeLimit = Instant.now().minus(Duration.ofDays(14L));

        return env.event().getInteraction().getChannel()
                .ofType(GuildMessageChannel.class)
                .zipWhen(c -> Mono.justOrEmpty(c.getLastMessageId()))
                .flatMapMany(TupleUtils.function((channel, lastMessageId) -> channel.getMessagesBefore(lastMessageId)
                        .take(count, true)
                        .filter(m -> m.getTimestamp().isAfter(timeLimit))
                        .switchIfEmpty(messageService.err(env, "Не получилось удалить сообщения").thenMany(Flux.never()))
                        .collectList()
                        .flatMap(m -> Mono.defer(() -> {
                            if (m.size() == 1) {
                                return m.get(0).delete();
                            }
                            return channel.bulkDeleteMessages(Flux.fromIterable(m)).then();
                        }).then(messageService.text(env, "Удалено **%s** %s", m.size(),
                                messageService.getPluralized(env.context(), "common.plurals.message", m.size()))))));
    }
}
