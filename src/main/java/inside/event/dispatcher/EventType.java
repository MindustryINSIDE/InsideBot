package inside.event.dispatcher;

import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.TextChannel;
import inside.data.entity.LocalMember;
import org.joda.time.DateTime;
import reactor.core.publisher.*;
import reactor.util.annotation.*;

import java.util.*;

public final class EventType{

    private EventType(){}

    public static class MessageClearEvent extends BaseEvent{
        public final List<Message> history;
        public final TextChannel channel;
        public final Member member;
        public final int count;

        public MessageClearEvent(Guild guild, List<Message> history, Member member, TextChannel channel, int count){
            super(guild);
            this.history = Objects.requireNonNull(history, "history");
            this.channel = Objects.requireNonNull(channel, "channel");
            this.member = Objects.requireNonNull(member, "member");
            this.count = count;
        }

        @Override
        public String toString(){
            return "MessageClearEvent{" +
                   "history=" + history +
                   ", channel=" + channel +
                   ", user=" + member +
                   ", count=" + count +
                   ", guild=" + guild +
                   '}';
        }
    }

    public static class MemberUnmuteEvent extends BaseEvent{
        public final LocalMember localMember;

        public MemberUnmuteEvent(Guild guild, LocalMember localMember){
            super(guild);
            this.localMember = Objects.requireNonNull(localMember, "localMember");
        }

        @Override
        public String toString(){
            return "MemberUnmuteEvent{" +
                   "localMember=" + localMember +
                   ", guild=" + guild +
                   '}';
        }
    }

    public static class MemberMuteEvent extends BaseEvent{
        public final LocalMember admin;
        public final LocalMember target;
        public final DateTime delay;
        private final @Nullable String reason;

        public MemberMuteEvent(Guild guild, LocalMember admin, LocalMember target, @Nullable String reason, DateTime delay){
            super(guild);
            this.admin = Objects.requireNonNull(admin, "admin");
            this.target = Objects.requireNonNull(target, "target");
            this.delay = Objects.requireNonNull(delay, "delay");
            this.reason = reason;
        }

        public Optional<String> reason(){
            return Optional.ofNullable(reason);
        }

        @Override
        public String toString(){
            return "MemberMuteEvent{" +
                   "admin=" + admin +
                   ", target=" + target +
                   ", reason='" + reason + '\'' +
                   ", delay=" + delay +
                   '}';
        }
    }
}
