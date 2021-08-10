package inside.command;

import com.udojava.evalex.*;
import discord4j.common.ReactorResources;
import discord4j.common.util.*;
import discord4j.core.object.audit.AuditLogEntry;
import discord4j.core.object.component.*;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.*;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.core.spec.*;
import discord4j.discordjson.json.*;
import discord4j.rest.util.*;
import inside.Settings;
import inside.audit.*;
import inside.command.model.*;
import inside.data.entity.*;
import inside.openweather.json.CurrentWeatherData;
import inside.scheduler.job.RemindJob;
import inside.service.AdminService;
import inside.util.*;
import inside.util.codec.Base64Coder;
import inside.util.io.ReusableByteInputStream;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import reactor.bool.BooleanUtils;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.util.function.Tuples;

import java.math.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.*;
import java.util.stream.*;

import static inside.audit.Attribute.COUNT;
import static inside.audit.BaseAuditProvider.MESSAGE_TXT;
import static inside.command.Commands.TransliterationCommand.mapOf;
import static inside.service.MessageService.ok;
import static inside.util.ContextUtil.*;
import static reactor.function.TupleUtils.function;

public class Commands{

    private Commands(){

    }

    public static abstract class AdminCommand extends Command{
        @Autowired
        protected AdminService adminService;

        @Override
        public Mono<Boolean> filter(CommandEnvironment env){
            return adminService.isAdmin(env.getAuthorAsMember());
        }
    }

    public static abstract class OwnerCommand extends Command{
        @Override
        public Mono<Boolean> filter(CommandEnvironment env){
            Member member = env.getAuthorAsMember();

            Mono<Boolean> isOwner = member.getGuild().flatMap(Guild::getOwner)
                    .map(member::equals);

            Mono<Boolean> isGuildManager = member.getHighestRole()
                    .map(role -> role.getPermissions().contains(Permission.MANAGE_GUILD));

            return BooleanUtils.or(isOwner, isGuildManager);
        }
    }

    //region common

    @DiscordCommand(key = {"help", "?", "man"}, params = "command.help.params", description = "command.help.description")
    public static class HelpCommand extends Command{
        @Autowired
        private CommandHolder commandHolder;

        private final Lazy<Map<CommandCategory, List<Map.Entry<Command, CommandInfo>>>> categoriesWithCommands = Lazy.of(() ->
                commandHolder.getCommandInfoMap().entrySet().stream()
                        .collect(Collectors.groupingBy(e -> e.getValue().category())));

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Optional<String> category = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(String::toLowerCase);

            Collector<CommandInfo, StringBuilder, StringBuilder> categoryCollector = Collector.of(StringBuilder::new,
                    (builder, info) -> {
                        builder.append("**");
                        builder.append(info.key()[0]);
                        builder.append("**");
                        if(info.key().length > 1){
                            StringJoiner joiner = new StringJoiner(", ");
                            for(int i = 1; i < info.key().length; i++){
                                joiner.add(info.key()[i]);
                            }
                            builder.append(" (").append(joiner).append(")");
                        }
                        if(info.params().length > 0){
                            builder.append(" ");
                            builder.append(messageService.get(env.context(), info.paramText()));
                        }
                        builder.append(" - ");
                        builder.append(messageService.get(env.context(), info.description()));
                        builder.append("\n");
                    },
                    StringBuilder::append);

            Mono<Void> categories = Flux.fromIterable(categoriesWithCommands.get().entrySet())
                    .distinct(Map.Entry::getKey)
                    .filterWhen(entry -> Flux.fromIterable(entry.getValue())
                            .filterWhen(e -> e.getKey().filter(env))
                            .hasElements())
                    .map(e -> String.format("• %s (`%s`)%n", messageService.getEnum(env.context(), e.getKey()), e.getKey()))
                    .collect(Collectors.joining())
                    .map(s -> s.concat("\n").concat(messageService.get(env.context(), "command.help.disclaimer.get-list")))
                    .flatMap(categoriesStr -> messageService.info(env, spec ->
                            spec.title(messageService.get(env.context(), "command.help"))
                                    .description(categoriesStr)));

            Mono<Void> snowHelp = Mono.defer(() -> {
                String unwrapped = category.orElse("");
                return categoriesWithCommands.get().keySet().stream()
                        .min(Comparator.comparingInt(s -> Strings.levenshtein(s.name(), unwrapped)))
                        .map(s -> messageService.err(env, "command.help.found-closest", s))
                        .orElse(messageService.err(env, "command.help.unknown"));
            });

            return Mono.justOrEmpty(category)
                    .mapNotNull(s -> Try.ofCallable(() -> CommandCategory.valueOf(s)).orElse(null))
                    .switchIfEmpty(categories.then(Mono.never()))
                    .mapNotNull(categoriesWithCommands.get()::get)
                    .switchIfEmpty(snowHelp.then(Mono.never()))
                    .filterWhen(entry -> Flux.fromIterable(entry)
                            .filterWhen(e -> e.getKey().filter(env))
                            .hasElements())
                    .switchIfEmpty(messageService.err(env, "command.help.unknown").then(Mono.never()))
                    .flatMapMany(Flux::fromIterable)
                    .map(Map.Entry::getValue)
                    .sort((o1, o2) -> Arrays.compare(o1.key(), o2.key()))
                    .collect(categoryCollector)
                    .map(builder -> builder.append(messageService.get(env.context(), "command.help.disclaimer.user"))
                            .append("\n").append(messageService.get(env.context(), "command.help.disclaimer.help")))
                    .flatMap(str -> messageService.info(env, spec -> spec.title(messageService.getEnum(env.context(),
                                    category.map(CommandCategory::valueOf).orElseThrow(IllegalStateException::new)))
                            .description(str.toString())));
        }
    }

    @DiscordCommand(key = "ping", description = "command.ping.description")
    public static class PingCommand extends Command{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            long start = System.currentTimeMillis();
            return env.getReplyChannel()
                    .flatMap(channel -> channel.createMessage(
                            messageService.get(env.context(), "command.ping.testing")))
                    .flatMap(message -> message.edit(MessageEditSpec.builder()
                            .contentOrNull(messageService.format(env.context(), "command.ping.completed",
                                    System.currentTimeMillis() - start))
                            .build()))
                    .then();
        }
    }

    //endregion
    //region text util

    @DiscordCommand(key = "google", params = "command.google.params", description = "command.google.description")
    public static class GoogleCommand extends Command{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String text = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            return messageService.text(env, "https://www.google.com/search?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
        }
    }

    @DiscordCommand(key = {"base64", "b64"}, params = "command.base64.params", description = "command.base64.description")
    public static class Base64Command extends Command{
        private final Lazy<HttpClient> httpClient = Lazy.of(ReactorResources.DEFAULT_HTTP_CLIENT);

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            boolean encode = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(str -> str.matches("^(?i)enc(ode)?$"))
                    .orElse(false);

            AtomicBoolean attachmentMode = new AtomicBoolean(false);

            Mono<String> handleAttachment = Mono.justOrEmpty(env.getMessage().getAttachments().stream()
                            .filter(att -> att.getWidth().isEmpty())
                            .filter(att -> att.getContentType().map(str -> str.startsWith("key")).orElse(true))
                            .findFirst())
                    .flatMap(att -> httpClient.get().get().uri(att.getUrl())
                            .responseSingle((res, buf) -> buf.asString()))
                    .doFirst(() -> attachmentMode.set(true));

            Mono<String> result = Mono.justOrEmpty(interaction.getOption(1)
                            .flatMap(CommandOption::getValue)
                            .map(OptionValue::asString))
                    .switchIfEmpty(handleAttachment)
                    .switchIfEmpty(messageService.err(env, "command.base64.missed-text").then(Mono.empty()))
                    .map(String::trim)
                    .flatMap(str -> Mono.fromCallable(() ->
                            encode ? Base64Coder.encodeString(str) : Base64Coder.decodeString(str)));

            return Mono.deferContextual(ctx -> result.onErrorResume(t -> t instanceof IllegalArgumentException,
                                    t -> messageService.err(env, t.getMessage()).then(Mono.empty()))
                            .flatMap(str -> messageService.text(env, spec -> {
                                if(str.isBlank()){
                                    spec.content(messageService.get(env.context(), "message.placeholder"));
                                }else if(str.length() < Message.MAX_CONTENT_LENGTH && !attachmentMode.get()){
                                    spec.content(str);
                                }else if(str.length() > Message.MAX_CONTENT_LENGTH || attachmentMode.get()){
                                    spec.addFile(MESSAGE_TXT, ReusableByteInputStream.ofString(str));
                                }
                            })))
                    .contextWrite(ctx -> ctx.put(KEY_REPLY, true));
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.base64.help",
                            GuildConfig.formatPrefix(prefix.get(0))));
        }
    }

    @DiscordCommand(key = {"translate", "tr"}, params = "command.translate.params", description = "command.translate.description")
    public static class TranslateCommand extends Command{
        public static final String[] languages = {
                "Afrikaans (`af`)", "Albanian (`sq`)", "Amharic (`am`)",
                "Arabic (`ar`)", "Armenian (`hy`)", "Automatic (`auto`)",
                "Azerbaijani (`az`)", "Basque (`eu`)", "Belarusian (`be`)",
                "Bengali (`bn`)", "Bosnian (`bs`)", "Bulgarian (`bg`)",
                "Catalan (`ca`)", "Cebuano (`ceb`)", "Chichewa (`ny`)",
                "Chinese Simplified (`zh-cn`)", "Chinese Traditional (`zh-tw`)", "Corsican (`co`)",
                "Croatian (`hr`)", "Czech (`cs`)", "Danish (`da`)",
                "Dutch (`nl`)", "English (`en`)", "Esperanto (`eo`)",
                "Estonian (`et`)", "Filipino (`tl`)", "Finnish (`fi`)",
                "French (`fr`)", "Frisian (`fy`)", "Galician (`gl`)",
                "Georgian (`ka`)", "German (`de`)", "Greek (`el`)",
                "Gujarati (`gu`)", "Haitian Creole (`ht`)", "Hausa (`ha`)",
                "Hawaiian (`haw`)", "Hebrew (`iw`)", "Hindi (`hi`)",
                "Hmong (`hmn`)", "Hungarian (`hu`)", "Icelandic (is)",
                "Igbo (`ig`)", "Indonesian (`id`)", "Irish (ga)",
                "Italian (`it`)", "Japanese (`ja`)", "Javanese (jw)",
                "Kannada (`kn`)", "Kazakh (`kk`)", "Khmer (km)",
                "Korean (`ko`)", "Kurdish (Kurmanji) (`ku`)", "Kyrgyz (`ky`)",
                "Lao (`lo`)", "Latin (`la`)", "Latvian (`lv`)",
                "Lithuanian (`lt`)", "Luxembourgish (`lb`)", "Macedonian (`mk`)",
                "Malagasy (`mg`)", "Malay (`ms`)", "Malayalam (`ml`)",
                "Maltese (`mt`)", "Maori (`mi`)", "Marathi (`mr`)",
                "Mongolian (`mn`)", "Myanmar (Burmese) (`my`)", "Nepali (`ne`)",
                "Norwegian (`no`)", "Pashto (`ps`)", "Persian (`fa`)",
                "Polish (`pl`)", "Portuguese (`pt`)", "Punjabi (`ma`)",
                "Romanian (`ro`)", "Russian (`ru`)", "Samoan (`sm`)",
                "Scots Gaelic (`gd`)", "Serbian (`sr`)", "Sesotho (`st`)",
                "Shona (`sn`)", "Sindhi (`sd`)", "Sinhala (`si`)",
                "Slovak (`sk`)", "Slovenian (`sl`)", "Somali (`so`)",
                "Spanish (`es`)", "Sundanese (`su`)", "Swahili (`sw`)",
                "Swedish (`sv`)", "Tajik (`tg`)", "Tamil (`ta`)",
                "Telugu (`te`)", "Thai (`th`)", "Turkish (`tr`)",
                "Ukrainian (`uk`)", "Urdu (`ur`)", "Uzbek (`uz`)",
                "Vietnamese (`vi`)", "Welsh (`cy`)", "Xhosa (`xh`)",
                "Yiddish (`yi`)", "Yoruba (`yo`)", "Zulu (`zu`)"
        };
        private static final Lazy<String> cachedLanguages = Lazy.of(() -> {
            StringBuilder builder = new StringBuilder();
            for(int i = 0; i < languages.length; i++){
                builder.append(languages[i]);
                if(i != languages.length - 1){
                    builder.append(", ");
                }
                if(i % 5 == 0){
                    builder.append('\n');
                }
            }
            builder.trimToSize();
            return builder.toString();
        });
        private final Lazy<HttpClient> httpClient = Lazy.of(ReactorResources.DEFAULT_HTTP_CLIENT);

        // I hope this not for long
        private static String params(Map<String, Object> map){
            return map.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                            URLEncoder.encode(Objects.toString(entry.getValue()), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&", "?", ""));
        }

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String from = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            String to = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            String text = interaction.getOption(2)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            String paramstr = params(Map.of(
                    "client", "dict-chrome-ex",
                    "dt", "t", "ie", "UTF-8", "oe", "UTF-8",
                    "q", text, "tl", to, "sl", from
            ));

            return httpClient.get().get().uri("https://translate.google.com/translate_a/t" + paramstr)
                    .responseSingle((res, buf) -> buf.asString().flatMap(byteBuf -> Mono.fromCallable(() ->
                            env.getClient().rest().getCoreResources().getJacksonResources()
                                    .getObjectMapper().readTree(byteBuf))))
                    .map(node -> Optional.ofNullable(node.get("sentences"))
                            .map(arr -> arr.get(0))
                            .map(single -> single.get("trans").asText())
                            .orElseGet(() -> messageService.get(env.context(), "message.placeholder")))
                    .flatMap(str -> messageService.text(env, str))
                    .contextWrite(ctx -> ctx.put(KEY_REPLY, true));
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.translate.help",
                            GuildConfig.formatPrefix(prefix.get(0)),
                            cachedLanguages.get()));
        }
    }

    @DiscordCommand(key = "r", params = "command.text-layout.params", description = "command.text-layout.description")
    public static class TextLayoutCommand extends Command{
        private static final String[] engPattern;
        private static final String[] rusPattern;

        static{
            String eng = "Q-W-E-R-T-Y-U-I-O-P-A-S-D-F-G-H-J-K-L-Z-X-C-V-B-N-M";
            String rus = "Й-Ц-У-К-Е-Н-Г-Ш-Щ-З-Ф-Ы-В-А-П-Р-О-Л-Д-Я-Ч-С-М-И-Т-Ь";
            engPattern = (eng + "-" + eng.toLowerCase() + "-\\^-:-\\$-@-&-~-`-\\{-\\[-\\}-\\]-\"-'-<->-;-\\?-\\/-\\.-,-#").split("-");
            rusPattern = (rus + "-" + rus.toLowerCase() + "-:-Ж-;-\"-\\?-Ё-ё-Х-х-Ъ-ъ-Э-э-Б-Ю-ж-,-\\.-ю-б-№").split("-");
        }

        public static String text2rus(String text){
            for(int i = 0; i < engPattern.length; i++){
                text = text.replaceAll(engPattern[i], rusPattern[i]);
            }
            return text;
        }

        public static String text2eng(String text){
            for(int i = 0; i < rusPattern.length; i++){
                text = text.replaceAll(rusPattern[i], engPattern[i]);
            }
            return text;
        }

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            boolean en = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map("en"::equalsIgnoreCase)
                    .orElse(false);

            String text = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            return messageService.text(env, en ? text2rus(text) : text2eng(text))
                    .contextWrite(ctx -> ctx.put(KEY_REPLY, true));
        }
    }

    @DiscordCommand(key = {"leet", "1337"}, params = "command.1337.params", description = "command.1337.description")
    public static class LeetSpeakCommand extends Command{
        public static final Map<String, String> rusLeetSpeak;
        public static final Map<String, String> engLeetSpeak;

        static{
            rusLeetSpeak = mapOf(
                    "а", "4", "б", "6", "в", "8", "г", "g",
                    "д", "d", "е", "3", "ё", "3", "ж", "zh",
                    "з", "e", "и", "i", "й", "\\`i", "к", "k",
                    "л", "l", "м", "m", "н", "n", "о", "0",
                    "п", "p", "р", "r", "с", "c", "т", "7",
                    "у", "y", "ф", "f", "х", "x", "ц", "u,",
                    "ч", "ch", "ш", "w", "щ", "w,", "ъ", "\\`ь",
                    "ы", "ьi", "ь", "ь", "э", "э", "ю", "10",
                    "я", "9"
            );

            engLeetSpeak = mapOf(
                    "a", "4", "b", "8", "c", "c", "d", "d",
                    "e", "3", "f", "ph", "g", "9", "h", "h",
                    "i", "1", "j", "g", "k", "k", "l", "l",
                    "m", "m", "n", "n", "o", "0", "p", "p",
                    "q", "q", "r", "r", "s", "5", "t", "7",
                    "u", "u", "v", "v", "w", "w", "x", "x",
                    "y", "y", "z", "2"
            );
        }

        public static String leeted(String text, boolean russian){
            Map<String, String> map = russian ? rusLeetSpeak : engLeetSpeak;
            UnaryOperator<String> get = s -> {
                String result = Optional.ofNullable(map.get(s.toLowerCase()))
                        .or(map.entrySet().stream()
                                .filter(entry -> entry.getValue().equalsIgnoreCase(s))
                                .map(Map.Entry::getKey)::findFirst)
                        .orElse("");

                return s.chars().anyMatch(Character::isUpperCase) ? result.toUpperCase() : result;
            };

            int len = text.length();
            if(len == 1){
                return get.apply(text);
            }

            StringBuilder result = new StringBuilder();
            for(int i = 0; i < len; ){
                String c = text.substring(i, i <= len - 2 ? i + 2 : i + 1);
                String leeted = get.apply(c);
                if(Strings.isEmpty(leeted)){
                    leeted = get.apply(c.charAt(0) + "");
                    result.append(Strings.isEmpty(leeted) ? c.charAt(0) : leeted);
                    i++;
                }else{
                    result.append(leeted);
                    i += 2;
                }
            }
            return result.toString();
        }

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            boolean ru = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map("ru"::equalsIgnoreCase)
                    .orElse(false);

            String text = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(str -> leeted(str, ru))
                    .orElse("");

            return messageService.text(env, spec -> {
                if(text.isBlank()){
                    spec.content(messageService.get(env.context(), "message.placeholder"));
                }else if(text.length() >= Message.MAX_CONTENT_LENGTH){
                    spec.addFile(MESSAGE_TXT, ReusableByteInputStream.ofString(text));
                }else{
                    spec.content(text);
                }
            }).contextWrite(ctx -> ctx.put(KEY_REPLY, true));
        }
    }

    @DiscordCommand(key = "translit", params = "command.transliteration.params", description = "command.transliteration.description")
    public static class TransliterationCommand extends Command{
        public static final Map<String, String> translit;

        static{
            translit = mapOf(
                    "a", "а", "b", "б", "v", "в", "g", "г",
                    "d", "д", "e", "е", "yo", "ё", "zh", "ж",
                    "z", "з", "i", "и", "j", "й", "k", "к",
                    "l", "л", "m", "м", "n", "н", "o", "о",
                    "p", "п", "r", "р", "s", "с", "t", "т",
                    "u", "у", "f", "ф", "h", "х", "x", "кс",
                    "ts", "ц", "ch", "ч", "sh", "ш", "sh'", "щ",
                    "\\`", "ъ", "y'", "ы", "'", "ь", "e\\`", "э",
                    "yu", "ю", "ya", "я", "iy", "ий"
            );
        }

        @SuppressWarnings("unchecked")
        public static <K, V> Map<K, V> mapOf(Object... values){
            Objects.requireNonNull(values, "values");
            Preconditions.requireArgument((values.length & 1) == 0, "length is odd");
            Map<K, V> map = new HashMap<>();

            for(int i = 0; i < values.length / 2; ++i){
                map.put((K)values[i * 2], (V)values[i * 2 + 1]);
            }

            return Map.copyOf(map);
        }

        public static String translit(String text){
            UnaryOperator<String> get = s -> {
                String result = Optional.ofNullable(translit.get(s.toLowerCase()))
                        .or(translit.entrySet().stream()
                                .filter(entry -> entry.getValue().equalsIgnoreCase(s))
                                .map(Map.Entry::getKey)::findFirst)
                        .orElse("");

                return s.chars().anyMatch(Character::isUpperCase) ? result.toUpperCase() : result;
            };

            int len = text.length();
            if(len == 1){
                return get.apply(text);
            }

            StringBuilder result = new StringBuilder();
            for(int i = 0; i < len; ){
                String c = text.substring(i, i <= len - 2 ? i + 2 : i + 1);
                String translited = get.apply(c);
                if(Strings.isEmpty(translited)){
                    translited = get.apply(c.charAt(0) + "");
                    result.append(Strings.isEmpty(translited) ? c.charAt(0) : translited);
                    i++;
                }else{
                    result.append(translited);
                    i += 2;
                }
            }
            return result.toString();
        }

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String translited = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(TransliterationCommand::translit)
                    .orElse("");

            return messageService.text(env, spec -> {
                if(translited.isBlank()){
                    spec.content(messageService.get(env.context(), "message.placeholder"));
                }else if(translited.length() >= Message.MAX_CONTENT_LENGTH){
                    spec.addFile(MESSAGE_TXT, ReusableByteInputStream.ofString(translited));
                }else{
                    spec.content(translited);
                }
            }).contextWrite(ctx -> ctx.put(KEY_REPLY, true));
        }
    }

    //endregion
    //region misc

    @DiscordCommand(key = {"emoji", "emote"}, params = "command.emoji.params", description = "command.emoji.description")
    public static class EmojiCommand extends Command{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String text = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            return env.getAuthorAsMember().getGuild()
                    .flatMapMany(guild -> guild.getEmojis(EntityRetrievalStrategy.REST))
                    .filter(emoji -> emoji.asFormat().equals(text) || emoji.getName().equals(text) ||
                            emoji.getId().asString().equals(text)).next()
                    .switchIfEmpty(messageService.err(env, "command.emoji.not-found").then(Mono.empty()))
                    .flatMap(emoji -> messageService.info(env, embed -> embed.image(emoji.getImageUrl() + "?size=512")
                            .footer(messageService.format(env.context(), "common.id", emoji.getId().asString()), null)
                            .description(messageService.format(env.context(), "command.emoji.text", emoji.getName(), emoji.asFormat()))));
        }
    }

    @DiscordCommand(key = "avatar", params = "command.avatar.params", description = "command.avatar.description")
    public static class AvatarCommand extends Command{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Optional<OptionValue> firstOpt = interaction.getOption(0)
                    .flatMap(CommandOption::getValue);

            Mono<User> referencedUser = Mono.justOrEmpty(env.getMessage().getMessageReference())
                    .flatMap(ref -> Mono.justOrEmpty(ref.getMessageId()).flatMap(messageId ->
                            env.getClient().withRetrievalStrategy(EntityRetrievalStrategy.REST)
                                    .getMessageById(ref.getChannelId(), messageId)))
                    .flatMap(message -> Mono.justOrEmpty(message.getAuthor()));

            return Mono.justOrEmpty(firstOpt.map(OptionValue::asSnowflake)).flatMap(id -> env.getClient()
                            .withRetrievalStrategy(EntityRetrievalStrategy.REST).getUserById(id))
                    .switchIfEmpty(referencedUser)
                    .switchIfEmpty(env.getClient().withRetrievalStrategy(EntityRetrievalStrategy.REST)
                            .getUserById(env.getAuthorAsMember().getId())
                            .filter(ignored -> firstOpt.isEmpty()))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.empty()))
                    .flatMap(user -> messageService.info(env, embed -> embed.image(user.getAvatarUrl() + "?size=512")
                            .description(messageService.format(env.context(), "command.avatar.text", user.getUsername(),
                                    user.getMention()))));
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.avatar.help",
                            GuildConfig.formatPrefix(prefix.get(0))));
        }
    }

    @DiscordCommand(key = {"math", "calc"}, params = "command.math.params", description = "command.math.description")
    public static class MathCommand extends Command{
        private static final LazyOperator divideAlias = new AbstractOperator(":", Expression.OPERATOR_PRECEDENCE_MULTIPLICATIVE, true){
            @Override
            public BigDecimal eval(BigDecimal v1, BigDecimal v2){
                return v1.divide(v2, MathContext.DECIMAL32);
            }
        };
        private static final LazyFunction factorialFunction = new AbstractLazyFunction("FACT", 1){
            @Override
            public Expression.LazyNumber lazyEval(List<Expression.LazyNumber> lazyParams){
                var fist = lazyParams.get(0);
                if(fist.eval().longValue() > 100){
                    throw new ArithmeticException("The number is too big!");
                }

                return createNumber(() -> {
                    int number = lazyParams.get(0).eval().intValue();
                    BigDecimal factorial = BigDecimal.ONE;
                    for(int i = 1; i <= number; i++){
                        factorial = factorial.multiply(new BigDecimal(i));
                    }
                    return factorial;
                });
            }
        };
        private static final LazyFunction levenshteinDstFunction = new AbstractLazyFunction("LEVEN", 2){
            @Override
            public Expression.LazyNumber lazyEval(List<Expression.LazyNumber> lazyParams){
                var first = lazyParams.get(0);
                var second = lazyParams.get(1);
                return createNumber(() -> BigDecimal.valueOf(Strings.levenshtein(first.getString(), second.getString())));
            }
        };

        public static Mono<BigDecimal> createExpression(String text){
            return Mono.fromCallable(() -> {
                Expression exp = new Expression(text);
                exp.addOperator(divideAlias);
                exp.addLazyFunction(levenshteinDstFunction);
                exp.addLazyFunction(factorialFunction);
                return exp.eval();
            });
        }

        private static Expression.LazyNumber createNumber(Supplier<BigDecimal> bigDecimal){
            return new Expression.LazyNumber(){
                @Override
                public BigDecimal eval(){
                    return bigDecimal.get();
                }

                @Override
                public String getString(){
                    return eval().toPlainString();
                }
            };
        }

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String text = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            return createExpression(text).publishOn(Schedulers.boundedElastic())
                    .onErrorResume(t -> t instanceof ArithmeticException || t instanceof Expression.ExpressionException ||
                                    t instanceof NumberFormatException,
                            t -> messageService.errTitled(env, "command.math.error.title", t.getMessage()).then(Mono.empty()))
                    .map(BigDecimal::toString)
                    .flatMap(decimal -> messageService.text(env, spec -> {
                        if(decimal.isBlank()){
                            spec.content(messageService.get(env.context(), "message.placeholder"));
                        }else if(decimal.length() >= Message.MAX_CONTENT_LENGTH){
                            spec.addFile(MESSAGE_TXT, ReusableByteInputStream.ofString(decimal));
                        }else{
                            spec.content(decimal);
                        }
                    }));
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.math.help",
                            GuildConfig.formatPrefix(prefix.get(0))));
        }
    }

    @DiscordCommand(key = {"random", "rand", "rnd"}, params = "command.random.params", description = "command.random.description")
    public static class RandomCommand extends Command{
        private static final Pattern rangePattern = Pattern.compile("^[(\\[]([-+]?[0-9]+);([-+]?[0-9]+)[])]$");

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String range = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            Matcher matcher = rangePattern.matcher(range);
            if(!matcher.matches()){
                return messageService.err(env, "command.random.incorrect-format");
            }

            String fgroup = matcher.group(1);
            String sgroup = matcher.group(2);
            if(!Strings.canParseLong(fgroup) || !Strings.canParseLong(sgroup)){
                return messageService.err(env, "command.random.overflow");
            }

            boolean linc = range.startsWith("[");
            long lower = Strings.parseLong(fgroup);
            boolean hinc = range.endsWith("]");
            long higher = Strings.parseLong(sgroup);

            if(lower >= higher){
                return messageService.err(env, "command.random.equals");
            }

            String str = String.valueOf(ThreadLocalRandom.current().nextLong(lower + (!linc ? 1 : 0), higher + (hinc
                    ? 1
                    : 0)));
            return messageService.text(env, str);
        }
    }

    @DiscordCommand(key = "poll", params = "command.poll.params", description = "command.poll.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.ADD_REACTIONS})
    public static class PollCommand extends Command{

        public static final ReactionEmoji[] emojis;

        static{
            emojis = new ReactionEmoji[]{
                    ReactionEmoji.unicode("1\u20E3"),
                    ReactionEmoji.unicode("2\u20E3"),
                    ReactionEmoji.unicode("3\u20E3"),
                    ReactionEmoji.unicode("3\u20E3"),
                    ReactionEmoji.unicode("4\u20E3"),
                    ReactionEmoji.unicode("5\u20E3"),
                    ReactionEmoji.unicode("6\u20E3"),
                    ReactionEmoji.unicode("7\u20E3"),
                    ReactionEmoji.unicode("8\u20E3"),
                    ReactionEmoji.unicode("9\u20E3"),
                    ReactionEmoji.unicode("\uD83D\uDD1F")
            };
        }

        @Autowired
        private Settings settings;

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Mono<MessageChannel> channel = env.getReplyChannel();
            Member author = env.getAuthorAsMember();

            String text = interaction.getOption("poll key")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            String[] vars = text.split("(?<!\\\\)" + Pattern.quote(","));
            String title = vars.length > 0 ? vars[0] : null;
            if(Strings.isEmpty(title)){
                return messageService.err(env, "command.poll.title").then(Mono.empty());
            }

            int count = vars.length - 1;
            if(count <= 0){
                return messageService.err(env, "command.poll.empty-variants");
            }

            if(count > emojis.length){
                return messageService.err(env, "common.limit-number", emojis.length - 1);
            }

            BiFunction<Message, Integer, Mono<Message>> reactions = (message, integer) -> Flux.fromArray(emojis)
                    .take(integer, true)
                    .flatMap(message::addReaction)
                    .then(Mono.just(message));

            return channel.flatMap(reply -> reply.createMessage(MessageCreateSpec.builder()
                            .allowedMentions(AllowedMentions.suppressAll())
                            .addEmbed(EmbedCreateSpec.builder()
                                    .title(title)
                                    .color(settings.getDefaults().getNormalColor())
                                    .description(IntStream.range(1, vars.length)
                                            .mapToObj(i -> String.format("**%d**. %s%n", i, vars[i]))
                                            .collect(Collectors.joining()))
                                    .author(author.getUsername(), null, author.getAvatarUrl())
                                    .build())
                            .build()))
                    .flatMap(poll -> Mono.defer(() -> reactions.apply(poll, count)))
                    .then();
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.poll.help",
                            GuildConfig.formatPrefix(prefix.get(0))));
        }
    }

    @DiscordCommand(key = "qpoll", params = "command.qpoll.params", description = "command.qpoll.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.ADD_REACTIONS})
    public static class QuickPollCommand extends Command{
        public static final ReactionEmoji up = ReactionEmoji.unicode("\uD83D\uDC4D");
        public static final ReactionEmoji down = ReactionEmoji.unicode("\uD83D\uDC4E");

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String text = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            return env.getReplyChannel().flatMap(reply -> reply.createMessage(MessageCreateSpec.builder()
                            .content(messageService.format(env.context(),
                                    "command.qpoll.text", env.getAuthorAsMember().getUsername(), text))
                            .allowedMentions(AllowedMentions.suppressAll())
                            .build()))
                    .flatMap(message1 -> message1.addReaction(up)
                            .and(message1.addReaction(down)));
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.qpoll.help",
                            GuildConfig.formatPrefix(prefix.get(0))));
        }
    }

    @DiscordCommand(key = "remind", params = "command.remind.params", description = "command.remind.description")
    public static class RemindCommand extends Command{
        @Autowired
        private SchedulerFactoryBean schedulerFactoryBean;

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            ZonedDateTime time = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asDateTime)
                    .orElse(null);

            String text = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            if(time == null){
                return messageService.err(env, "message.error.invalid-time");
            }

            Member member = env.getAuthorAsMember();
            JobDetail job = RemindJob.createDetails(member.getGuildId(), member.getId(),
                    env.getMessage().getChannelId(), text);

            Trigger trigger = TriggerBuilder.newTrigger()
                    .startAt(Date.from(time.toInstant()))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule())
                    .build();

            Try.run(() -> schedulerFactoryBean.getScheduler().scheduleJob(job, trigger));
            return env.getMessage().addReaction(ok);
        }
    }

    @DiscordCommand(key = "link", params = "command.link.params", description = "command.link.description")
    public static class LinkCommand extends Command{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Snowflake channelId = env.getMessage().getChannelId();
            String link = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            String text = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            return env.getClient().rest().getApplicationId()
                    .flatMap(applicationId -> env.getClient().rest().getWebhookService()
                            .getChannelWebhooks(channelId.asLong())
                            .filter(data -> data.applicationId().map(id -> id.asLong() == applicationId).orElse(false)
                                    && data.name().map(s -> env.getAuthorAsMember().getDisplayName().equals(s)).orElse(false))
                            .next()
                            .switchIfEmpty(env.getClient().rest().getWebhookService()
                                    .createWebhook(channelId.asLong(), WebhookCreateRequest.builder()
                                            .name(env.getAuthorAsMember().getDisplayName())
                                            .avatarOrNull(env.getAuthorAsMember().getAvatarUrl())
                                            .build(), null))
                            .flatMap(webhookData -> env.getClient().rest().getWebhookService()
                                    .executeWebhook(webhookData.id().asLong(), webhookData.token().get(), false,
                                            MultipartRequest.ofRequest(WebhookExecuteRequest.builder()
                                                    .avatarUrl(env.getAuthorAsMember().getAvatarUrl())
                                                    .content("[" + text + "](" + link + ")")
                                                    .allowedMentions(AllowedMentions.suppressAll().toData())
                                                    .build()))))
                    .then(env.getMessage().delete());
        }
    }

    @DiscordCommand(key = "weather", params = "command.weather.params", description = "command.weather.description")
    public static class WeatherCommand extends Command{
        private static final float mmHg = 133.322f;
        private static final float hPa = 100;

        private final Lazy<HttpClient> httpClient = Lazy.of(ReactorResources.DEFAULT_HTTP_CLIENT);

        @Autowired
        private Settings settings;

        // I hope this not for long
        private static String params(Map<String, Object> map){
            return map.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                            URLEncoder.encode(Objects.toString(entry.getValue()), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&", "?", ""));
        }

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            String city = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElseThrow(IllegalStateException::new);

            String paramstr = params(Map.of(
                    "q", city,
                    "units", "metric",
                    "lang", env.context().get(KEY_LOCALE).toString(),
                    "appid", settings.getDiscord().getOpenweatherApiKey()
            ));

            return httpClient.get().get().uri("api.openweathermap.org/data/2.5/weather" + paramstr)
                    .responseSingle((res, buf) -> res.status() == HttpResponseStatus.NOT_FOUND
                            ? messageService.err(env, "command.weather.not-found").then(Mono.never())
                            : buf.asString())
                    .flatMap(str -> Mono.fromCallable(() ->
                            env.getClient().rest().getCoreResources().getJacksonResources()
                                    .getObjectMapper().readValue(str, CurrentWeatherData.class)))
                    .flatMap(data -> messageService.info(env, spec -> spec.description(messageService.format(env.context(),
                            "command.weather.format", data.weather().get(0).description(),
                            data.main().temperature(), data.main().temperatureMin(), data.main().temperatureMax(),
                            data.main().pressure() * hPa / mmHg, data.main().humidity(),
                            data.visibility(), data.clouds().all(), data.wind().speed(),
                            TimestampFormat.LONG_DATE_TIME.format(Instant.ofEpochSecond(data.dateTime()))))))
                    .contextWrite(ctx -> ctx.put(KEY_REPLY, true));
        }
    }

    //endregion
    //region settings

    @DiscordCommand(key = "prefix", params = "command.settings.prefix.params", description = "command.settings.prefix.description",
            category = CommandCategory.owner)
    public static class PrefixCommand extends OwnerCommand{

        private static final Pattern modePattern = Pattern.compile("^(add|remove|clear)$", Pattern.CASE_INSENSITIVE);

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member member = env.getAuthorAsMember();

            String mode = interaction.getOption(0)
                    .flatMap(CommandOption::getChoice)
                    .map(OptionValue::asString)
                    .filter(s -> modePattern.matcher(s).matches())
                    .orElse(null);

            String value = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElse(null);

            return entityRetriever.getGuildConfigById(member.getGuildId())
                    .switchIfEmpty(entityRetriever.createGuildConfig(member.getGuildId()))
                    .flatMap(guildConfig -> Mono.defer(() -> {
                        List<String> prefixes = guildConfig.prefixes();
                        if(mode == null){
                            return messageService.text(env, "command.settings.prefix.current",
                                    String.join(", ", prefixes));
                        }else if(mode.equalsIgnoreCase("add")){
                            if(value == null){
                                return messageService.err(env, "command.settings.prefix-absent");
                            }
                            prefixes.add(value);
                            return messageService.text(env, "command.settings.added", value);
                        }else if(mode.equalsIgnoreCase("remove")){
                            if(value == null){
                                return messageService.err(env, "command.settings.prefix-absent");
                            }
                            prefixes.remove(value);
                            return messageService.text(env, "command.settings.removed", value);
                        }else{ // clear
                            // ignore value, it doesn't matter
                            prefixes.clear();
                            return messageService.text(env, "command.settings.prefix.clear");
                        }
                    }).and(entityRetriever.save(guildConfig)));
        }
    }

    @DiscordCommand(key = "timezone", params = "command.settings.timezone.params", description = "command.settings.timezone.description",
            category = CommandCategory.owner)
    public static class TimezoneCommand extends OwnerCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member member = env.getAuthorAsMember();

            boolean present = interaction.getOption(0).isPresent();

            ZoneId timeZone = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .flatMap(str -> Try.ofCallable(() -> ZoneId.of(str)).toOptional())
                    .orElse(null);

            String str = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .orElse("");

            return entityRetriever.getGuildConfigById(member.getGuildId())
                    .switchIfEmpty(entityRetriever.createGuildConfig(member.getGuildId()))
                    .filter(ignored -> present)
                    .switchIfEmpty(messageService.text(env, "command.settings.timezone.current",
                            env.context().<Locale>get(KEY_TIMEZONE)).then(Mono.empty()))
                    .flatMap(guildConfig -> Mono.defer(() -> {
                        if(timeZone == null){
                            return ZoneId.getAvailableZoneIds().stream()
                                    .min(Comparator.comparingInt(s -> Strings.levenshtein(s, str)))
                                    .map(s -> messageService.err(env, "command.settings.timezone.unknown.suggest", s))
                                    .orElse(messageService.err(env, "command.settings.timezone.unknown"));
                        }

                        guildConfig.timeZone(timeZone);
                        return Mono.deferContextual(ctx -> messageService.text(env,
                                        "command.settings.timezone.update", ctx.<Locale>get(KEY_TIMEZONE)))
                                .contextWrite(ctx -> ctx.put(KEY_TIMEZONE, timeZone))
                                .and(entityRetriever.save(guildConfig));
                    }));
        }
    }

    @DiscordCommand(key = "locale", params = "command.settings.locale.params", description = "command.settings.locale.description",
            category = CommandCategory.owner)
    public static class LocaleCommand extends OwnerCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member member = env.getAuthorAsMember();

            boolean present = interaction.getOption(0).isPresent();

            Locale locale = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .flatMap(messageService::getLocale)
                    .orElse(null);

            return entityRetriever.getGuildConfigById(member.getGuildId())
                    .switchIfEmpty(entityRetriever.createGuildConfig(member.getGuildId()))
                    .filter(guildConfig -> present)
                    .switchIfEmpty(messageService.text(env, "command.settings.locale.current",
                            env.context().<Locale>get(KEY_LOCALE).getDisplayName()).then(Mono.empty()))
                    .flatMap(guildConfig -> {
                        if(locale == null){
                            String all = messageService.getSupportedLocales().values().stream()
                                    .map(locale1 -> String.format("%s (`%s`)", locale1.getDisplayName(), locale1))
                                    .collect(Collectors.joining(", "));

                            return messageService.text(env, "command.settings.locale.all", all);
                        }

                        guildConfig.locale(locale);
                        return Mono.deferContextual(ctx -> messageService.text(env, "command.settings.locale.update",
                                        ctx.<Locale>get(KEY_LOCALE).getDisplayName()))
                                .contextWrite(ctx -> ctx.put(KEY_LOCALE, locale))
                                .and(entityRetriever.save(guildConfig));
                    });
        }
    }

    //endregion
    //region moderation

    @DiscordCommand(key = "mute", params = "command.admin.mute.params", description = "command.admin.mute.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.MANAGE_ROLES},
            category = CommandCategory.admin)
    public static class MuteCommand extends AdminCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member author = env.getAuthorAsMember();

            Optional<Snowflake> targetId = interaction.getOption("@user")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            Snowflake guildId = author.getGuildId();

            ZonedDateTime delay = interaction.getOption("delay")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asDateTime)
                    .orElse(null);

            if(delay == null){
                return messageService.err(env, "message.error.invalid-time");
            }

            String reason = interaction.getOption("reason")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(String::trim)
                    .orElse(null);

            return entityRetriever.getAdminConfigById(guildId)
                    .switchIfEmpty(entityRetriever.createAdminConfig(guildId))
                    .filter(adminConfig -> adminConfig.getMuteRoleID().isPresent())
                    .switchIfEmpty(messageService.err(env, "command.disabled.mute").then(Mono.never()))
                    .flatMap(ignored -> Mono.justOrEmpty(targetId)).flatMap(id -> env.getClient().getMemberById(guildId, id))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filter(Predicate.not(User::isBot))
                    .switchIfEmpty(messageService.err(env, "common.bot").then(Mono.never()))
                    .filterWhen(member -> BooleanUtils.not(adminService.isMuted(member)))
                    .switchIfEmpty(messageService.err(env, "command.admin.mute.already-muted").then(Mono.never()))
                    .filterWhen(member -> Mono.zip(adminService.isAdmin(member), adminService.isOwner(author))
                            .map(function((admin, owner) -> !(admin && !owner))))
                    .switchIfEmpty(messageService.err(env, "command.admin.user-is-admin").then(Mono.never()))
                    .flatMap(member -> {
                        if(author.equals(member)){
                            return messageService.err(env, "command.admin.mute.self-user");
                        }

                        if(reason != null && !reason.isBlank() && reason.length() >= AuditLogEntry.MAX_REASON_LENGTH){
                            return messageService.err(env, "common.string-limit", AuditLogEntry.MAX_REASON_LENGTH);
                        }

                        return adminService.mute(author, member, delay.toInstant(), reason)
                                .and(env.getMessage().addReaction(ok));
                    });
        }
    }

    @DiscordCommand(key = "unmute", params = "command.admin.unmute.params", description = "command.admin.unmute.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.ADD_REACTIONS, Permission.MANAGE_ROLES},
            category = CommandCategory.admin)
    public static class UnmuteCommand extends AdminCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Optional<Snowflake> targetId = interaction.getOption("@user")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            Snowflake guildId = env.getAuthorAsMember().getGuildId();

            return entityRetriever.getAdminConfigById(guildId)
                    .switchIfEmpty(entityRetriever.createAdminConfig(guildId))
                    .filter(adminConfig -> adminConfig.getMuteRoleID().isPresent())
                    .switchIfEmpty(messageService.err(env, "command.disabled.mute").then(Mono.never()))
                    .flatMap(ignored -> Mono.justOrEmpty(targetId))
                    .flatMap(id -> env.getClient().getMemberById(guildId, id))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filterWhen(adminService::isMuted)
                    .switchIfEmpty(messageService.err(env, "audit.member.unmute.is-not-muted").then(Mono.never()))
                    .flatMap(target -> adminService.unmute(target).and(env.getMessage().addReaction(ok)));
        }
    }

    @DiscordCommand(key = "delete", params = "command.admin.delete.params", description = "command.admin.delete.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.ADD_REACTIONS,
                           Permission.MANAGE_MESSAGES, Permission.READ_MESSAGE_HISTORY},
            category = CommandCategory.admin)
    public static class DeleteCommand extends AdminCommand{
        @Autowired
        private Settings settings;

        @Autowired
        private AuditService auditService;

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member author = env.getAuthorAsMember();
            Mono<GuildMessageChannel> reply = env.getReplyChannel().cast(GuildMessageChannel.class);

            Optional<String> arg = interaction.getOption("count")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString);

            if(arg.filter(MessageUtil::canParseInt).isEmpty()){
                return messageService.err(env, "command.incorrect-number");
            }

            long number = arg.map(Strings::parseLong).orElse(0L);
            if(number > settings.getDiscord().getMaxClearedCount()){
                return messageService.err(env, "common.limit-number", settings.getDiscord().getMaxClearedCount());
            }

            StringBuffer result = new StringBuffer();
            Instant limit = Instant.now().minus(14, ChronoUnit.DAYS);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss")
                    .withLocale(env.context().get(KEY_LOCALE))
                    .withZone(env.context().get(KEY_TIMEZONE));

            BiConsumer<Message, Member> appendInfo = (message, member) -> {
                result.append("[").append(formatter.format(message.getTimestamp())).append("] ");
                if(DiscordUtil.isBot(member)){
                    result.append("[BOT] ");
                }

                result.append(member.getUsername());
                member.getNickname().ifPresent(nickname -> result.append(" (").append(nickname).append(")"));
                result.append(" >");
                String content = MessageUtil.effectiveContent(message);
                if(!content.isBlank()){
                    result.append(" ").append(content);
                }
                if(!message.getEmbeds().isEmpty()){
                    result.append(" (... ").append(message.getEmbeds().size()).append(" embed(s))");
                }
                result.append("\n");
            };

            Mono<Void> history = reply.flatMapMany(channel -> channel.getMessagesBefore(env.getMessage().getId())
                            .take(number, true)
                            .sort(Comparator.comparing(Message::getId))
                            .filter(message -> message.getTimestamp().isAfter(limit))
                            .flatMap(message -> message.getAuthorAsMember()
                                    .doOnNext(member -> appendInfo.accept(message, member))
                                    .flatMap(ignored -> entityRetriever.deleteMessageInfoById(message.getId()))
                                    .thenReturn(message))
                            .transform(messages -> number > 1 ? channel.bulkDeleteMessages(messages).then() :
                                    messages.next().flatMap(Message::delete).then()))
                    .then();

            Mono<Void> log = reply.flatMap(channel -> auditService.newBuilder(author.getGuildId(), AuditActionType.MESSAGE_CLEAR)
                    .withUser(author)
                    .withChannel(channel)
                    .withAttribute(COUNT, number)
                    .withAttachment(MESSAGE_TXT, ReusableByteInputStream.ofString(result.toString()))
                    .save());

            return history.then(log).and(env.getMessage().addReaction(ok));
        }

        @Override
        public Mono<Void> help(CommandEnvironment env){
            return entityRetriever.getGuildConfigById(env.getAuthorAsMember().getGuildId())
                    .map(GuildConfig::prefixes)
                    .flatMap(prefix -> messageService.infoTitled(env, "command.help.title", "command.admin.delete.help",
                            GuildConfig.formatPrefix(prefix.get(0))));
        }
    }

    @DiscordCommand(key = "warn", params = "command.admin.warn.params", description = "command.admin.warn.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.BAN_MEMBERS},
            category = CommandCategory.admin)
    public static class WarnCommand extends AdminCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member author = env.getAuthorAsMember();

            Optional<Snowflake> targetId = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            String reason = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(String::trim)
                    .orElse(null);

            Snowflake guildId = author.getGuildId();

            return Mono.justOrEmpty(targetId).flatMap(id -> env.getClient().getMemberById(guildId, id))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filter(Predicate.not(User::isBot))
                    .switchIfEmpty(messageService.err(env, "common.bot").then(Mono.never()))
                    .filterWhen(target -> Mono.zip(adminService.isAdmin(target), adminService.isOwner(author))
                            .map(function((admin, owner) -> !(admin && !owner))))
                    .switchIfEmpty(messageService.err(env, "command.admin.user-is-admin").then(Mono.never()))
                    .flatMap(member -> {
                        if(author.equals(member)){
                            return messageService.err(env, "command.admin.warn.self-user");
                        }

                        if(!Strings.isEmpty(reason) && reason.length() >= AuditLogEntry.MAX_REASON_LENGTH){
                            return messageService.err(env, "common.string-limit", AuditLogEntry.MAX_REASON_LENGTH);
                        }

                        Mono<Void> warnings = Mono.defer(() -> adminService.warnings(member).count()).flatMap(count -> {
                            Mono<Void> message = messageService.text(env, "command.admin.warn", member.getUsername(), count);

                            Mono<AdminConfig> config = entityRetriever.getAdminConfigById(guildId)
                                    .switchIfEmpty(entityRetriever.createAdminConfig(guildId));

                            Mono<Void> thresholdCheck = config.filter(adminConfig -> count >= adminConfig.getMaxWarnCount())
                                    .flatMap(adminConfig -> switch(adminConfig.getThresholdAction()){
                                        case ban -> author.getGuild().flatMap(guild ->
                                                guild.ban(member.getId(), BanQuerySpec.builder()
                                                        .deleteMessageDays(0)
                                                        .build()));
                                        case kick -> author.kick();
                                        case mute -> author.getGuild().flatMap(Guild::getOwner)
                                                .flatMap(owner -> adminService.mute(owner, author,
                                                        Instant.now().plus(adminConfig.getMuteBaseDelay()), null));
                                        default -> Mono.empty();
                                    });

                            return message.then(thresholdCheck);
                        });

                        return adminService.warn(author, member, reason).then(warnings);
                    });
        }
    }

    @DiscordCommand(key = "softban", params = "command.admin.softban.params", description = "command.admin.softban.description",
            permissions = {Permission.SEND_MESSAGES, Permission.EMBED_LINKS, Permission.ADD_REACTIONS, Permission.BAN_MEMBERS},
            category = CommandCategory.admin)
    public static class SoftbanCommand extends AdminCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member author = env.getAuthorAsMember();

            Optional<Snowflake> targetId = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            Optional<String> days = interaction.getOption(1)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString);

            if(days.isPresent() && days.filter(MessageUtil::canParseInt).isEmpty()){
                return messageService.err(env, "command.admin.softban.incorrect-days");
            }

            int deleteDays = days.map(Strings::parseInt).orElse(0);
            if(deleteDays > 7){
                DurationFormatter formatter = DurationFormat.wordBased(env.context().get(KEY_LOCALE));
                return messageService.err(env, "command.admin.softban.days-limit",
                        formatter.format(Duration.ofDays(7)));
            }

            String reason = interaction.getOption(2)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString)
                    .map(String::trim)
                    .orElse(null);

            Snowflake guildId = author.getGuildId();

            return Mono.justOrEmpty(targetId).flatMap(id -> env.getClient().getMemberById(guildId, id))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filter(Predicate.not(User::isBot))
                    .switchIfEmpty(messageService.err(env, "common.bot").then(Mono.never()))
                    .filterWhen(target -> Mono.zip(adminService.isAdmin(target), adminService.isOwner(author))
                            .map(function((admin, owner) -> !(admin && !owner))))
                    .switchIfEmpty(messageService.err(env, "command.admin.user-is-admin").then(Mono.never()))
                    .flatMap(member -> member.getGuild().flatMap(guild -> guild.ban(member.getId(), BanQuerySpec.builder()
                                    .reason(reason)
                                    .deleteMessageDays(deleteDays)
                                    .build()))
                            .then(member.getGuild().flatMap(guild -> guild.unban(member.getId()))))
                    .and(env.getMessage().addReaction(ok));
        }
    }

    @DiscordCommand(key = "warnings", params = "command.admin.warnings.params", description = "command.admin.warnings.description",
            category = CommandCategory.admin)
    public static class WarningsCommand extends AdminCommand{
        public static final int PER_PAGE = 9;

        @Autowired
        private Settings settings;

        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Optional<Snowflake> targetId = interaction.getOption(0)
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            Mono<Member> referencedUser = Mono.justOrEmpty(env.getMessage().getMessageReference())
                    .flatMap(ref -> Mono.justOrEmpty(ref.getMessageId()).flatMap(messageId ->
                            env.getClient().getMessageById(ref.getChannelId(), messageId)))
                    .flatMap(Message::getAuthorAsMember);

            Snowflake guildId = env.getAuthorAsMember().getGuildId();

            Snowflake authorId = env.getAuthorAsMember().getId();

            return Mono.justOrEmpty(targetId)
                    .flatMap(userId -> env.getClient().getMemberById(guildId, userId))
                    .switchIfEmpty(referencedUser)
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filter(Predicate.not(User::isBot))
                    .switchIfEmpty(messageService.err(env, "common.bot").then(Mono.never()))
                    .zipWhen(member -> adminService.warnings(member)
                            .switchIfEmpty(messageService.text(env, "command.admin.warnings.empty").then(Mono.never()))
                            .take(PER_PAGE, true).index()
                            .map(function((idx, warn) ->
                                    EmbedCreateFields.Field.of(String.format("%2s. %s", idx + 1,
                                                    TimestampFormat.LONG_DATE_TIME.format(warn.getTimestamp())), String.format("%s%n%s",
                                                    messageService.format(env.context(), "common.admin", warn.getAdmin().effectiveName()),
                                                    messageService.format(env.context(), "common.reason", warn.getReason()
                                                            .orElse(messageService.get(env.context(), "common.not-defined")))),
                                            true)))
                            .collectList())
                    .zipWhen(tuple -> adminService.warnings(tuple.getT1()).count(),
                            (tuple, count) -> Tuples.of(tuple.getT1(), tuple.getT2(), count))
                    .flatMap(function((target, fields, count) -> messageService.text(env, spec -> spec.addEmbed(EmbedCreateSpec.builder()
                                    .fields(fields)
                                    .title(messageService.get(env.context(), "command.admin.warnings.title"))
                                    .color(settings.getDefaults().getNormalColor())
                                    .footer(String.format("Страница 1/%d", Mathf.ceilPositive(count / (float)PER_PAGE)), null)
                                    .build())
                            .addComponent(ActionRow.of(
                                    Button.primary("inside-warnings-" + authorId.asString() +
                                                    "-" + target.getId().asString() + "-prev-0",
                                            messageService.get(env.context(), "common.prev-page")).disabled(),
                                    Button.primary("inside-warnings-" + authorId.asString() +
                                                    "-" + target.getId().asString() + "-next-1",
                                            messageService.get(env.context(), "common.prev-page")).disabled(count <= PER_PAGE))))));
        }
    }

    @DiscordCommand(key = "unwarn", params = "command.admin.unwarn.params", description = "command.admin.unwarn.description",
            category = CommandCategory.admin)
    public static class UnwarnCommand extends AdminCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member author = env.getAuthorAsMember();

            Optional<Snowflake> targetId = interaction.getOption("@user")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            Snowflake guildId = author.getGuildId();

            Optional<String> index = interaction.getOption("index")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asString);

            if(index.filter(MessageUtil::canParseInt).isEmpty()){
                return messageService.err(env, "command.incorrect-number");
            }

            return Mono.justOrEmpty(targetId).flatMap(id -> env.getClient().getMemberById(guildId, id))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filter(Predicate.not(User::isBot))
                    .switchIfEmpty(messageService.err(env, "common.bot").then(Mono.never()))
                    .filterWhen(target -> adminService.isOwner(author).map(owner -> !target.equals(author) || owner))
                    .switchIfEmpty(messageService.err(env, "command.admin.unwarn.permission-denied").then(Mono.never()))
                    .flatMap(target -> adminService.warnings(target).count().flatMap(count -> {
                        int warn = index.map(Strings::parseInt).orElse(1);
                        if(count == 0){
                            return messageService.text(env, "command.admin.warnings.empty");
                        }

                        if(warn > count){
                            return messageService.err(env, "command.incorrect-number");
                        }

                        return messageService.text(env, "command.admin.unwarn", target.getUsername(), warn)
                                .and(adminService.unwarn(target, warn - 1));
                    }));
        }
    }

    @DiscordCommand(key = "unwarnall", params = "command.admin.unwarnall.params", description = "command.admin.unwarnall.description",
            category = CommandCategory.admin)
    public static class UnwarnAllCommand extends AdminCommand{
        @Override
        public Mono<Void> execute(CommandEnvironment env, CommandInteraction interaction){
            Member author = env.getAuthorAsMember();

            Optional<Snowflake> targetId = interaction.getOption("@user")
                    .flatMap(CommandOption::getValue)
                    .map(OptionValue::asSnowflake);

            Snowflake guildId = author.getGuildId();

            return Mono.justOrEmpty(targetId).flatMap(id -> env.getClient().getMemberById(guildId, id))
                    .switchIfEmpty(messageService.err(env, "command.incorrect-name").then(Mono.never()))
                    .filter(Predicate.not(User::isBot))
                    .switchIfEmpty(messageService.err(env, "common.bot").then(Mono.never()))
                    .filterWhen(target -> adminService.isOwner(author).map(owner -> !target.equals(author) || owner))
                    .switchIfEmpty(messageService.err(env, "command.admin.unwarnall.permission-denied").then(Mono.never())) // pluralized variant
                    .flatMap(target -> messageService.text(env, "command.admin.unwarnall", target.getUsername())
                            .then(adminService.unwarnAll(guildId, target.getId())));
        }
    }

    //endregion
}
