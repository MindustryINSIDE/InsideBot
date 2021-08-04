package inside.data.entity;

import discord4j.common.util.Snowflake;
import inside.data.entity.base.GuildEntity;
import org.hibernate.annotations.Type;
import org.joda.time.DateTime;
import reactor.util.annotation.NonNull;

import javax.persistence.*;
import java.io.Serial;
import java.util.*;

@Entity
@Table(name = "message_info")
public class MessageInfo extends GuildEntity{
    @Serial
    private static final long serialVersionUID = -7977287922184407665L;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "user_id")
    private String userId;

    @Column(columnDefinition = "text")
    private String content;

    @Column
    @Type(type = "date-time")
    private DateTime timestamp;

    public Snowflake messageId(){
        return Snowflake.of(messageId);
    }

    public void messageId(Snowflake messageId){
        this.messageId = Objects.requireNonNull(messageId, "messageId").asString();
    }

    public Snowflake userId(){
        return Snowflake.of(userId);
    }

    public void userId(Snowflake userId){
        this.userId = Objects.requireNonNull(userId, "userId").asString();
    }

    public String content(){
        return content;
    }

    public void content(String content){
        this.content = Objects.requireNonNull(content, "content");
    }

    public DateTime timestamp(){
        return timestamp;
    }

    public void timestamp(DateTime timestamp){
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public String toString(){
        return "MessageInfo{" +
               "messageId='" + messageId + '\'' +
               ", userId='" + userId + '\'' +
               ", content='" + content + '\'' +
               ", timestamp=" + timestamp +
               "} " + super.toString();
    }
}
