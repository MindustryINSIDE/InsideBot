package inside.util;

import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Message;
import org.joda.time.DateTime;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.*;

public abstract class MessageUtil{

    private static final Pattern timeUnitPattern = Pattern.compile(
            "^" +
            "((\\d+)(y|year|years|г|год|года|лет))?" +
            "((\\d+)(m|mon|month|months|мес|месяц|месяца|месяцев))?" +
            "((\\d+)(w|week|weeks|н|нед|неделя|недели|недель|неделю))?" +
            "((\\d+)(d|day|days|д|день|дня|дней))?" +
            "((\\d+)(h|hour|hours|ч|час|часа|часов))?" +
            "((\\d+)(min|mins|minute|minutes|мин|минута|минуту|минуты|минут))?" +
            "((\\d+)(s|sec|secs|second|seconds|с|c|сек|секунда|секунду|секунды|секунд))?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern durationTimeUnitPattern = Pattern.compile(
            "^" +
            "((\\d+)(d|day|days|д|день|дня|дней))?" +
            "((\\d+)(h|hour|hours|ч|час|часа|часов))?" +
            "((\\d+)(m|min|mins|minute|minutes|мин|минута|минуту|минуты|минут))?" +
            "((\\d+)(s|sec|secs|second|seconds|с|c|сек|секунда|секунду|секунды|секунд))?$",
            Pattern.CASE_INSENSITIVE
    );

    private MessageUtil(){}

    public static boolean isEmpty(@Nullable Message message){
        return message == null || effectiveContent(message).isEmpty();
    }

    public static String substringTo(String message, int maxLength){
        Objects.requireNonNull(message, "message");
        return message.length() >= maxLength ? message.substring(0, maxLength - 4) + "..." : message;
    }

    public static String effectiveContent(Message message){
        Objects.requireNonNull(message, "message");

        StringBuilder builder = new StringBuilder();
        if(!Strings.isEmpty(message.getContent())){
            builder.append(message.getContent());
        }

        if(!message.getAttachments().isEmpty()){
            builder.append("\n---\n");
            message.getAttachments().forEach(a -> builder.append(a.getUrl()).append("\n"));
        }
        return builder.toString();
    }

    public static boolean canParseInt(String message){
        return Strings.parseInt(message) > 0;
    }

    @Nullable
    public static Snowflake parseUserId(String message){
        try{
            return Snowflake.of(message.replaceAll("[<>@!]", ""));
        }catch(Throwable t){
            Exceptions.throwIfJvmFatal(t);
            return null;
        }
    }

    @Nullable
    public static Duration parseDuration(String message){
        Matcher matcher = durationTimeUnitPattern.matcher(message);
        if(!matcher.matches()){
            try{
                return Duration.parse(message);
            }catch(Throwable ignored){}
            return null;
        }

        return Duration.ZERO
                .plus(Strings.parseLong(matcher.group(11), 0), ChronoUnit.SECONDS)
                .plus(Strings.parseLong(matcher.group(8), 0), ChronoUnit.MINUTES)
                .plus(Strings.parseLong(matcher.group(5), 0), ChronoUnit.HOURS)
                .plus(Strings.parseLong(matcher.group(2), 0), ChronoUnit.DAYS);
    }

    @Nullable
    public static DateTime parseTime(String message){
        Matcher matcher = timeUnitPattern.matcher(message);
        if(!matcher.matches()){
            return null;
        }

        return DateTime.now().plus(LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
                .plus(Strings.parseLong(matcher.group(2), 0), ChronoUnit.YEARS)
                .plus(Strings.parseLong(matcher.group(5), 0), ChronoUnit.MONTHS)
                .plus(Strings.parseLong(matcher.group(8), 0), ChronoUnit.WEEKS)
                .plus(Strings.parseLong(matcher.group(11), 0), ChronoUnit.DAYS)
                .plus(Strings.parseLong(matcher.group(14), 0), ChronoUnit.HOURS)
                .plus(Strings.parseLong(matcher.group(17), 0), ChronoUnit.MINUTES)
                .plus(Strings.parseLong(matcher.group(20), 0), ChronoUnit.SECONDS)
                .toEpochSecond(ZoneOffset.UTC) * 1000);
    }
}
