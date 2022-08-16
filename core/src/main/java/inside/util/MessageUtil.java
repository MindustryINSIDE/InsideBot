package inside.util;

import discord4j.common.util.Snowflake;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.InteractionFollowupCreateSpec;
import discord4j.core.spec.InteractionReplyEditSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.discordjson.json.EmojiData;
import io.r2dbc.postgresql.codec.Interval;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

public abstract class MessageUtil {

    private MessageUtil() {}

    public static String substringTo(String message, int maxLength) {
        return message.length() >= maxLength ? message.substring(0, maxLength - 4) + "..." : message;
    }

    public static String getEmojiString(ReactionEmoji emoji) {
        return emoji.asUnicodeEmoji().map(ReactionEmoji.Unicode::getRaw)
                .orElseGet(() -> emoji.asCustomEmoji()
                        .map(ReactionEmoji.Custom::asFormat)
                        .orElseThrow());
    }

    public static String getEmojiString(EmojiData data) {
        String name = data.name().orElseThrow(IllegalArgumentException::new);
        if (data.id().isPresent()) {
            return String.format("<%s:%s:%s>", data.animated().toOptional()
                            .map(bool -> bool ? "a" : "").orElse(""),
                    name, data.id().get());
        }
        return name;
    }

    @Nullable
    public static Snowflake parseId(String id) {
        try {
            return Snowflake.of(id);
        } catch (Throwable t) {
            Exceptions.throwIfJvmFatal(t);
            return null;
        }
    }

    public static String getUserMention(long id) {
        return "<@" + Snowflake.asString(id) + '>';
    }

    public static String getUserMention(Snowflake id) {
        return "<@" + id.asString() + ">";
    }

    public static String getMemberMention(Snowflake id) {
        return "<@!" + id.asString() + ">";
    }

    public static String getRoleMention(Snowflake id) {
        return "<@&" + id.asString() + ">";
    }

    public static String getRoleMention(long id) {
        return "<@&" + Snowflake.asString(id) + ">";
    }

    public static String getChannelMention(long id) {
        return "<#" + Snowflake.asString(id) + ">";
    }

    public static String getChannelMention(Snowflake id) {
        return "<#" + id.asString() + ">";
    }

    public static InteractionFollowupCreateSpec toFollowupCreateSpec(MessageCreateSpec spec) {
        return InteractionFollowupCreateSpec.builder()
                .content(spec.content())
                .embeds(spec.embedsOrElse(List.of()))
                .components(spec.components())
                .allowedMentions(spec.allowedMentions())
                .tts(spec.ttsOrElse(false))
                .files(spec.files())
                .fileSpoilers(spec.fileSpoilers())
                .build();
    }

    public static InteractionReplyEditSpec toReplyEditSpec(MessageCreateSpec spec) {
        return InteractionReplyEditSpec.builder()
                .contentOrNull(spec.content().toOptional().orElse(null))
                .embedsOrNull(spec.embeds().toOptional().orElse(null))
                .componentsOrNull(spec.components().toOptional().orElse(null))
                .allowedMentionsOrNull(spec.allowedMentions().toOptional().orElse(null))
                .files(spec.files())
                .fileSpoilers(spec.fileSpoilers())
                .build();
    }

    @Nullable
    public static Interval parseInterval(String text) {
        try {
            text = text.trim();

            Interval tryDef = Interval.parse(text);
            if (tryDef != Interval.ZERO) {
                return tryDef;
            }

            // Скопировано с Interval#parse, но поддерживает русский язык в названиях единиц измерения
            int years = 0;
            int months = 0;
            int days = 0;
            int hours = 0;
            int minutes = 0;
            double seconds = 0;
            String valueToken;
            StringTokenizer st = new StringTokenizer(text);
            while (st.hasMoreTokens()) {
                String token = st.nextToken();

                int endHours = token.indexOf(':');
                if (endHours == -1) {
                    valueToken = token;
                } else {
                    int offset = token.charAt(0) == '-' ? 1 : 0;

                    hours = Integer.parseInt(token.substring(offset, endHours));
                    minutes = Integer.parseInt(token.substring(endHours + 1, endHours + 3));

                    int endMinutes = token.indexOf(':', endHours + 1);
                    if (endMinutes != -1) {
                        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.ROOT);
                        seconds = numberFormat.parse(token.substring(endMinutes + 1)).doubleValue();
                    }

                    if (offset == 1) {
                        hours = -hours;
                        minutes = -minutes;
                        seconds = -seconds;
                    }
                    break;
                }

                if (!st.hasMoreTokens()) {
                    return null;
                }

                token = st.nextToken().toLowerCase(Locale.ROOT);

                switch (token) {
                    case "г", "год", "лет" -> years = Integer.parseInt(valueToken);
                    case "мес", "месяц", "месяцев", "месяца" -> months = Integer.parseInt(valueToken);
                    case "д", "день", "дня", "дней" -> days = Integer.parseInt(valueToken);
                    case "ч", "час", "часа", "часов" -> hours = Integer.parseInt(valueToken);
                    case "м", "мин", "минуту", "минут", "минуты" -> minutes = Integer.parseInt(valueToken);
                    case "с", "сек", "секунд", "секунду", "секунда", "секунды" -> seconds = Double.parseDouble(valueToken);
                }
            }

            return Interval.of(years, months, days, hours, minutes, seconds);
        } catch (Throwable t) {
            Exceptions.throwIfJvmFatal(t);
            return null;
        }
    }
}
