package inside;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import discord4j.common.JacksonResources;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import discord4j.rest.http.client.ClientException;
import discord4j.rest.request.RouteMatcher;
import discord4j.rest.response.ResponseFunction;
import discord4j.rest.route.Routes;
import discord4j.rest.util.AllowedMentions;
import inside.command.CommandHandler;
import inside.command.CommandHolder;
import inside.data.CacheEntityRetriever;
import inside.data.DatabaseResources;
import inside.data.EntityRetrieverImpl;
import inside.data.RepositoryHolder;
import inside.data.api.DefaultRepositoryFactory;
import inside.data.api.EntityOperations;
import inside.data.api.codec.LocaleCodec;
import inside.data.api.r2dbc.DefaultDatabaseClient;
import inside.data.entity.ModerationAction;
import inside.data.schedule.*;
import inside.event.InteractionEventHandler;
import inside.event.MessageEventHandler;
import inside.event.ReactionRoleEventHandler;
import inside.event.StarboardEventHandler;
import inside.interaction.chatinput.InteractionCommand;
import inside.interaction.chatinput.InteractionCommandHolder;
import inside.interaction.chatinput.InteractionGuildCommand;
import inside.interaction.chatinput.common.*;
import inside.interaction.chatinput.guild.EmojiCommand;
import inside.interaction.chatinput.guild.LeaderboardCommand;
import inside.interaction.chatinput.moderation.DeleteCommand;
import inside.interaction.chatinput.moderation.WarnCommand;
import inside.interaction.chatinput.moderation.WarnsCommand;
import inside.interaction.chatinput.settings.*;
import inside.service.InteractionService;
import inside.service.MessageService;
import inside.service.task.ActivityTask;
import inside.util.func.UnsafeRunnable;
import inside.util.json.AdapterModule;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.codec.EnumCodec;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.R2dbcBadGrammarException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;
import sun.misc.Signal;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static reactor.function.TupleUtils.function;

public class Launcher {
    public static final AllowedMentions suppressAll = AllowedMentions.suppressAll();

    private static final Logger log = Loggers.getLogger(Launcher.class);

    private static GatewayDiscordClient gateway;

    public static GatewayDiscordClient getClient() {
        return gateway;
    }

    private static void printBanner() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (var banner = loader.getResourceAsStream("banner.txt")) {
            Objects.requireNonNull(banner, "banner");
            System.out.println(new String(banner.readAllBytes()));
        } catch (Throwable t) {
            log.error("Failed to print banner.", t);
        }
    }

    public static void main(String[] args) {
        long pre = System.currentTimeMillis();
        printBanner();

        JacksonResources jacksonResources = JacksonResources.create()
                .withMapperFunction(objectMapper -> objectMapper
                        .registerModule(new JavaTimeModule())
                        .registerModule(new AdapterModule())
                        .registerModule(new ParameterNamesModule()));

        JacksonResources polymorphicJacksonResources = JacksonResources.create()
                .withMapperFunction(objectMapper -> objectMapper
                        .registerModule(new JavaTimeModule())
                        .registerModule(new AdapterModule())
                        .registerModule(new ParameterNamesModule()))
                .withMapperFunction(objectMapper -> objectMapper.activateDefaultTypingAsProperty(
                        BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                        ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT,
                        "@type"));

        Configuration configuration;
        File cfg = new File("configuration.json");
        try {
            if (cfg.exists()) {
                configuration = jacksonResources.getObjectMapper().readValue(cfg, Configuration.class);
            } else {
                String json = jacksonResources.getObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ImmutableConfiguration.builder()
                                .token("<your token>")
                                .build());

                Files.writeString(cfg.toPath(), json);
                log.info("Bot configuration created.");
                return;
            }
        } catch (Throwable t) {
            log.error("Failed to resolve bot configuration.", t);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Configuration: {}", configuration);
        }

        Hooks.onNextError((t, o) -> {
            if (t instanceof R2dbcBadGrammarException p) {
                log.error("Failed to execute SQL '{}'", p.getOffendingSql());
            }
            return t;
        });

        String url = configuration.database().url();
        String user = configuration.database().user();
        String password = configuration.database().password();

        var parsed = ConnectionFactoryOptions.parse(url);
        var psqlConfiguration = PostgresqlConnectionConfiguration.builder()
                .codecRegistrar(EnumCodec.builder()
                        .withEnum("moderation_action_type", ModerationAction.Type.class)
                        .withEnum("trigger_state", Trigger.State.class)
                        .build())
                .codecRegistrar((connection, alloc, registry) -> Mono.fromRunnable(() -> {
                    registry.addLast(new LocaleCodec(alloc));
                }))
                .username(user)
                .password(password)
                // ???! Зачем они убрали дженерик?
                .port((Integer) parsed.getRequiredValue(ConnectionFactoryOptions.PORT))
                .host((String) parsed.getRequiredValue(ConnectionFactoryOptions.HOST))
                .database((String) parsed.getRequiredValue(ConnectionFactoryOptions.DATABASE))
                .build();

        var factory = new PostgresqlConnectionFactory(psqlConfiguration);

        var poolConfiguration = ConnectionPoolConfiguration.builder(factory)
                .maxSize(15)
                .maxLifeTime(Duration.ofSeconds(10))
                .build();
        var pool = new ConnectionPool(poolConfiguration);

        var entityOperations = new EntityOperations(jacksonResources);
        var databaseClient = new DefaultDatabaseClient(pool);
        var databaseResources = new DatabaseResources(databaseClient, entityOperations);

        var repositoryFactory = new DefaultRepositoryFactory(databaseResources);
        var repositoryHolder = new RepositoryHolder(repositoryFactory);

        var entityRetriever = new CacheEntityRetriever(new EntityRetrieverImpl(configuration, repositoryHolder));

        ReactiveScheduler scheduler = new ReactiveSchedulerImpl(databaseClient,
                new SchedulerResources(Schedulers.boundedElastic(), "inside-scheduler"),
                polymorphicJacksonResources.getObjectMapper(),
                new DefaultJobFactory());

        DiscordClient.builder(configuration.token())
                .onClientResponse(ResponseFunction.emptyIfNotFound())
                .onClientResponse(ResponseFunction.emptyOnErrorStatus(RouteMatcher.route(Routes.REACTION_CREATE), 400))
                .setDefaultAllowedMentions(suppressAll)
                .build()
                .gateway()
                .setEnabledIntents(IntentSet.of(
                        Intent.GUILDS,
                        Intent.GUILD_MEMBERS,
                        Intent.GUILD_MESSAGES,
                        Intent.GUILD_VOICE_STATES,
                        Intent.GUILD_MESSAGE_REACTIONS,
                        Intent.DIRECT_MESSAGES,
                        Intent.GUILD_INVITES
                ))
                .withGateway(gateway -> {
                    Launcher.gateway = gateway;

                    // shard-aware resources
                    // services
                    var messageService = new MessageService(gateway, configuration);
                    var interactionService = new InteractionService(gateway, configuration, messageService, entityRetriever);

                    var interactionCommandHolder = InteractionCommandHolder.builder()
                            // разное
                            .addCommand(new MathCommand(messageService))
                            .addCommand(new PingCommand(messageService))
                            .addCommand(new AvatarCommand(messageService))
                            .addCommand(new LeetSpeakCommand(messageService))
                            .addCommand(new TextLayoutCommand(messageService))
                            .addCommand(new TransliterationCommand(messageService))
                            .addCommand(new RemindCommand(messageService, scheduler))
                            // разное, но серверное
                            .addCommand(new EmojiCommand(messageService))
                            .addCommand(new LeaderboardCommand(messageService, entityRetriever))
                            // настройки
                            .addCommand(new ActivityCommand(messageService, entityRetriever))
                            .addCommand(new ReactionRolesCommand(messageService, entityRetriever))
                            .addCommand(new StarboardCommand(messageService, entityRetriever))
                            .addCommand(new GuildConfigCommand(messageService, entityRetriever))
                            .addCommand(new AdminConfigCommand(messageService, entityRetriever))
                            // админские
                            .addCommand(new DeleteCommand(messageService, entityRetriever))
                            .addCommand(new WarnCommand(messageService, entityRetriever))
                            .addCommand(new WarnsCommand(messageService, entityRetriever))
                            .build();

                    var cmds = interactionCommandHolder.getCommands().values();
                    List<ApplicationCommandRequest> globalCommands = new ArrayList<>();
                    List<ApplicationCommandRequest> guildCommands = new ArrayList<>();
                    for (InteractionCommand value : cmds) {
                        if (value instanceof InteractionGuildCommand) {
                            guildCommands.add(value.getRequest());
                        } else {
                            globalCommands.add(value.getRequest());
                        }
                    }

                    CommandHolder commandHolder = CommandHolder.builder()
                            .addCommand(new inside.command.common.AvatarCommand(messageService))
                            .addCommand(new inside.command.common.PingCommand(messageService))
                            .build();

                    CommandHandler commandHandler = new CommandHandler(entityRetriever, commandHolder,
                            messageService, configuration);

                    var handlers = ReactiveEventAdapter.from(
                            new InteractionEventHandler(configuration, interactionCommandHolder,
                                    interactionService, entityRetriever),
                            new MessageEventHandler(entityRetriever, commandHandler, configuration, interactionService, messageService),
                            new ReactionRoleEventHandler(entityRetriever),
                            new StarboardEventHandler(entityRetriever, messageService));

                    Mono<Void> registerCommands = Mono.zip(gateway.rest().getApplicationId(), Mono.just(globalCommands))
                            .flatMapMany(function((appId, glob) -> gateway.rest().getApplicationService()
                                    .bulkOverwriteGlobalApplicationCommand(appId, glob)))
                            .then(Mono.zip(gateway.rest().getApplicationId(), Mono.just(guildCommands))
                                    .flatMapMany(function((appId, guild) -> gateway.getGuilds()
                                            .flatMap(g -> gateway.rest().getApplicationService()
                                                    .bulkOverwriteGuildApplicationCommand(appId, g.getId().asLong(), guild)
                                                    .onErrorResume(e -> e instanceof ClientException c &&
                                                            c.getStatus().code() == 403, e -> Flux.empty())))) // missing access
                            .then());

                    Mono<Void> registerEvents = gateway.on(handlers)
                            .then();

                    Mono<Void> registerTasks = Flux.just(
                                    new ActivityTask(configuration, entityRetriever))
                            .flatMap(task -> Flux.interval(task.getInterval())
                                    .flatMap(l -> task.execute()))
                            .then();

                    return Mono.when(scheduler.start(), registerEvents, registerCommands, registerTasks)
                            .doFirst(() -> log.info("Bot bootstrapped in {} seconds.", (System.currentTimeMillis() - pre) / 1000f));
                })
                .onErrorStop()
                .doFirst(() -> Signal.handle(new Signal("INT"), sig -> {
                    run(() -> {
                        String json = jacksonResources.getObjectMapper()
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(configuration);

                        Files.writeString(cfg.toPath(), json);
                        log.info("Bot configuration saved.");
                    });
                    run(() -> {
                        if (gateway != null) {
                            gateway.logout().block();
                        }
                    });
                    run(pool::dispose);
                    run(() -> System.exit(1));
                }))
                .block();
    }

    private static void run(UnsafeRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            log.error("Failed to execute shutdown action.", t);
        }
    }
}
