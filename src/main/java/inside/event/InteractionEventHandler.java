package inside.event;

import discord4j.common.util.Snowflake;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.event.domain.interaction.*;
import inside.Settings;
import inside.data.service.EntityRetriever;
import inside.interaction.InteractionCommandEnvironment;
import inside.interaction.component.*;
import inside.service.*;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import reactor.util.context.Context;
import reactor.util.function.*;

import java.util.List;

import static inside.util.ContextUtil.*;
import static reactor.function.TupleUtils.*;

@Component
public class InteractionEventHandler extends ReactiveEventAdapter{

    @Autowired
    private EntityRetriever entityRetriever;

    @Autowired
    private MessageService messageService;

    @Autowired
    private Settings settings;

    @Autowired
    private DiscordService discordService;

    private List<Tuple2<String, ButtonListener>> buttonListeners;

    @Autowired(required = false)
    private void registerButtonListeners(List<ButtonListener> buttonListeners){
        this.buttonListeners = buttonListeners.stream()
                .map(l -> Tuples.of(l.getClass().getAnnotation(ComponentProvider.class).value(), l))
                .toList();
    }

    @Override
    public Publisher<?> onButtonInteraction(ButtonInteractionEvent event){
        String id = event.getCustomId();
        if(!id.startsWith("inside")){
            return Mono.empty();
        }

        Snowflake guildId = event.getInteraction().getGuildId().orElseThrow(IllegalStateException::new);

        Mono<Context> initContext = entityRetriever.getGuildConfigById(guildId)
                .switchIfEmpty(entityRetriever.createGuildConfig(guildId))
                .map(guildConfig -> Context.of(KEY_LOCALE, guildConfig.locale(),
                        KEY_TIMEZONE, guildConfig.timeZone()));

        return initContext.flatMap(ctx -> Mono.defer(() -> Flux.fromIterable(buttonListeners)
                        .filter(predicate((s, l) -> id.startsWith(s)))
                        .flatMap(function((s, l) -> l.handle(event)))
                        .then())
                .contextWrite(ctx));
    }

    @Override
    public Publisher<?> onApplicationCommandInteraction(ApplicationCommandInteractionEvent event){
        Mono<Context> initContext = Mono.justOrEmpty(event.getInteraction().getGuildId())
                .flatMap(guildId -> entityRetriever.getGuildConfigById(guildId)
                        .switchIfEmpty(entityRetriever.createGuildConfig(guildId)))
                .map(guildConfig -> Context.of(KEY_LOCALE, guildConfig.locale(),
                        KEY_TIMEZONE, guildConfig.timeZone()))
                .defaultIfEmpty(Context.of(KEY_LOCALE, messageService.getDefaultLocale(),
                        KEY_TIMEZONE, settings.getDefaults().getTimeZone()));

        return initContext.flatMap(context -> discordService.handle(InteractionCommandEnvironment.builder()
                        .event(event)
                        .context(context)
                        .build())
                .contextWrite(context));
    }
}
