package inside.service.impl;

import discord4j.common.store.Store;
import discord4j.common.store.legacy.LegacyStoreLayout;
import discord4j.common.util.Snowflake;
import discord4j.core.*;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.discordjson.json.PresenceData;
import discord4j.gateway.intent.*;
import discord4j.rest.request.RouteMatcher;
import discord4j.rest.response.ResponseFunction;
import discord4j.rest.route.Routes;
import discord4j.store.api.mapping.MappingStoreService;
import discord4j.store.api.noop.NoOpStoreService;
import discord4j.store.jdk.JdkStoreService;
import inside.Settings;
import inside.data.service.EntityRetriever;
import inside.interaction.*;
import inside.service.DiscordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;

import javax.annotation.*;
import javax.transaction.Transactional;
import java.util.*;

@Service
public class DiscordServiceImpl implements DiscordService{

    private GatewayDiscordClient gateway;

    @Autowired(required = false)
    private List<InteractionCommand> commands;

    @Autowired(required = false)
    private ReactiveEventAdapter[] adapters;

    @Autowired
    private Settings settings;

    @Autowired
    private EntityRetriever entityRetriever;

    @PostConstruct
    public void init(){
        String token = settings.getToken();
        Objects.requireNonNull(token, "token");
        // StoreServiceLoader storeServiceLoader = new StoreServiceLoader();

        gateway = DiscordClientBuilder.create(token)
                .onClientResponse(ResponseFunction.emptyIfNotFound())
                .onClientResponse(ResponseFunction.emptyOnErrorStatus(RouteMatcher.route(Routes.REACTION_CREATE), 400))
                .build()
                .gateway()
                .setStore(Store.fromLayout(LegacyStoreLayout.of(MappingStoreService.create()
                        .setMapping(new NoOpStoreService(), PresenceData.class)
                        // .setMapping(new CaffeineStoreService(caffeine -> caffeine.weakKeys()
                        //         .expireAfterWrite(Duration.ofDays(3))), MessageData.class)
                        .setFallback(new JdkStoreService()))))
                .setEnabledIntents(IntentSet.of(
                        Intent.GUILDS,
                        Intent.GUILD_MEMBERS,
                        Intent.GUILD_MESSAGES,
                        Intent.GUILD_VOICE_STATES,
                        Intent.GUILD_MESSAGE_REACTIONS,
                        Intent.DIRECT_MESSAGES
                ))
                .login()
                .block();

        Objects.requireNonNull(gateway, "impossible"); // for ide

        long applicationId = Objects.requireNonNull(gateway.rest().getApplicationId().block(), "impossible");
        for(InteractionCommand command : commands){
            gateway.rest().getApplicationService()
                    .createGlobalApplicationCommand(applicationId, command.getRequest())
                    .subscribe();
        }

        gateway.on(ReactiveEventAdapter.from(adapters)).subscribe();
    }

    @Override
    public Mono<Void> handle(InteractionCommandEnvironment env){
        return Mono.justOrEmpty(commands.stream()
                .filter(cmd -> cmd.getRequest().name().equals(env.event().getCommandName()))
                .findFirst())
                .filterWhen(cmd -> cmd.filter(env))
                .flatMap(cmd -> cmd.execute(env));
    }

    @PreDestroy
    public void destroy(){
        gateway.logout().block();
    }

    @Override
    public List<InteractionCommand> getCommands(){
        return commands;
    }

    @Override // for monitors
    public GatewayDiscordClient gateway(){
        return gateway;
    }

    @Override
    public Mono<TextChannel> getTextChannelById(Snowflake channelId){
        return gateway.getChannelById(channelId).ofType(TextChannel.class);
    }

    @Override
    @Transactional
    @Scheduled(cron = "* */2 * * * *")
    public void activeUsers(){
        entityRetriever.getAllLocalMembers()
                .filter(localMember -> localMember.activity().activeUserConfig().isEnable())
                .flatMap(localMember -> Mono.zip(Mono.just(localMember),
                        gateway.getMemberById(localMember.guildId(), localMember.userId())))
                .flatMap(TupleUtils.function((localMember, member) -> {
                    Snowflake roleId = localMember.activity()
                            .activeUserConfig().roleId().orElse(null); // asserted above
                    if(roleId == null){
                        return Mono.empty();
                    }

                    if(localMember.activity().isActive()){
                        return member.addRole(roleId);
                    }else{
                        return Mono.when(member.removeRole(roleId), Mono.fromRunnable(localMember.activity()::resetIfAfter),
                                entityRetriever.save(localMember));
                    }
                }))
                .subscribe();
    }
}
