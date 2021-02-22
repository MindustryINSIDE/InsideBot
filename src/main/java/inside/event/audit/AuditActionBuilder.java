package inside.event.audit;

import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.GuildChannel;
import inside.data.entity.*;
import inside.data.entity.base.NamedReference;
import reactor.core.publisher.Mono;
import reactor.util.function.*;

import java.io.InputStream;
import java.util.*;

public abstract class AuditActionBuilder{

    protected final AuditAction action;

    protected final List<Tuple2<String, InputStream>> attachments = new ArrayList<>();

    protected AuditActionBuilder(Snowflake guildId, AuditActionType type){
        action = new AuditAction(guildId);
        action.timestamp(Calendar.getInstance());
        action.type(type);
        action.attributes(new HashMap<>());
    }

    public AuditActionBuilder withUser(User user){
        action.user(getReference(user));
        return this;
    }

    public AuditActionBuilder withUser(Member user){
        action.user(getReference(user));
        return this;
    }

    public AuditActionBuilder withUser(LocalMember user){
        action.user(getReference(user));
        return this;
    }

    public AuditActionBuilder withTargetUser(User user){
        action.target(getReference(user));
        return this;
    }

    public AuditActionBuilder withTargetUser(Member user){
        action.target(getReference(user));
        return this;
    }

    public AuditActionBuilder withTargetUser(LocalMember user){
        action.target(getReference(user));
        return this;
    }

    public AuditActionBuilder withChannel(GuildChannel channel){
        action.channel(getReference(channel));
        return this;
    }

    public AuditActionBuilder withAttribute(String key, Object value){
        action.attributes().put(key, getReferenceForObject(value));
        return this;
    }

    public AuditActionBuilder withAttachment(String key, InputStream data){
        attachments.add(Tuples.of(key, data));
        return this;
    }

    private Object getReferenceForObject(Object object){
        if(object instanceof Member member){
            return getReference(member);
        }else if(object instanceof User user){
            return getReference(user);
        }else if(object instanceof LocalMember localMember){
            return getReference(localMember);
        }else if(object instanceof GuildChannel guildChannel){
            return getReference(guildChannel);
        }else{
            return object;
        }
    }

    private NamedReference getReference(Member member){
        return new NamedReference(member.getId(), member.getDisplayName());
    }

    private NamedReference getReference(User user){
        return new NamedReference(user.getId(), user.getUsername());
    }

    private NamedReference getReference(LocalMember member){
        return new NamedReference(member.userId(), member.effectiveName());
    }

    private NamedReference getReference(GuildChannel channel){
        return new NamedReference(channel.getId(), channel.getName());
    }

    public abstract Mono<Void> save();
}
