package inside.interaction;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.InteractionCreateEvent;
import discord4j.core.object.entity.channel.MessageChannel;
import org.immutables.builder.Builder;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

public class InteractionCommandEnvironment{
    private final InteractionCreateEvent event;
    private final ContextView context;

    @Builder.Constructor
    protected InteractionCommandEnvironment(InteractionCreateEvent event, ContextView context){
        this.event = event;
        this.context = context;
    }

    public static InteractionCommandEnvironmentBuilder builder(){
        return new InteractionCommandEnvironmentBuilder();
    }

    public InteractionCreateEvent event(){
        return event;
    }

    public ContextView context(){
        return context;
    }

    public Mono<MessageChannel> getReplyChannel(){
        return event.getInteraction().getChannel();
    }

    public GatewayDiscordClient getClient(){
        return event().getClient();
    }
}
