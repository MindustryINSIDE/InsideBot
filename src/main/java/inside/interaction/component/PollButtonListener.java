package inside.interaction.component;

import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.object.Embed;
import discord4j.core.object.entity.*;
import discord4j.core.spec.*;
import discord4j.rest.util.AllowedMentions;
import inside.data.entity.PollAnswer;
import inside.data.service.EntityRetriever;
import inside.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.*;

import static inside.util.ContextUtil.KEY_EPHEMERAL;

@ComponentProvider("inside-poll")
public class PollButtonListener implements ButtonListener{

    private final EntityRetriever entityRetriever;
    private final MessageService messageService;

    public PollButtonListener(@Autowired EntityRetriever entityRetriever,
                              @Autowired MessageService messageService){
        this.entityRetriever = entityRetriever;
        this.messageService = messageService;
    }

    @Override
    public Mono<Void> handle(ButtonInteractionEvent event){
        return Mono.deferContextual(ctx -> entityRetriever.getPollById(event.getMessageId())
                .flatMap(poll -> {
                    User user = event.getInteraction().getUser();
                    if(poll.getAnswered().stream().anyMatch(p -> p.getUserId().equals(user.getId()))) {
                        return messageService.err(event, "command.poll.already-answered")
                                .contextWrite(ctx0 -> ctx0.put(KEY_EPHEMERAL, true));
                    }

                    String[] parts = event.getCustomId().split("-");
                    int idx = Integer.parseInt(parts[2]); // [ inside, poll, 0 ]

                    Message message = event.getMessage().orElseThrow(ise);
                    List<Embed> embeds = message.getEmbeds();
                    Embed source = embeds.isEmpty() ? null : embeds.get(0);
                    var embedSpec = EmbedCreateSpec.builder();

                    if (source == null) { // embed removed and we can't handle interaction, TODO: implement recreating
                        return event.getInteractionResponse()
                                .deleteInitialResponse()
                                .and(entityRetriever.delete(poll));
                    } else {
                        embedSpec.author(source.getAuthor()
                                .map(author -> EmbedCreateFields.Author.of(
                                        author.getName().orElseThrow(ise), null,
                                        author.getIconUrl().orElseThrow(ise)))
                                .orElseThrow(ise));
                        embedSpec.title(source.getTitle().orElseThrow(ise));
                        embedSpec.color(source.getColor().orElseThrow(ise));
                        embedSpec.description(source.getDescription().orElseThrow(ise));
                    }

                    PollAnswer answer = new PollAnswer();
                    answer.setGuildId(event.getInteraction().getGuildId().orElseThrow(ise));
                    answer.setOption(idx);
                    answer.setUserId(user.getId());

                    poll.getAnswered().add(answer);

                    int count = poll.getAnswered().size();
                    Map<Integer, Integer> statistic = new LinkedHashMap<>();
                    for(PollAnswer pollAnswer : poll.getAnswered()){
                        statistic.compute(pollAnswer.getOption(), (s, i) -> i == null ? 1 : i + 1);
                    }

                    embedSpec.footer(statistic.entrySet().stream()
                            .map(e -> String.format("%s: %d%% (%d)",
                                    e.getKey(), (int)(count / 100f * e.getValue()), e.getValue()))
                            .collect(Collectors.joining("\n")), null);

                    return event.edit(InteractionApplicationCommandCallbackSpec.builder()
                                    .allowedMentions(AllowedMentions.suppressAll())
                                    .addEmbed(embedSpec.build())
                                    .build())
                            .then(entityRetriever.save(poll))
                            .then();
                }));
    }

    private static final Supplier<IllegalStateException> ise = IllegalStateException::new;
}
