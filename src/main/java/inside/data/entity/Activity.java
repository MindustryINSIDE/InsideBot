package inside.data.entity;

import inside.data.entity.base.GuildEntity;
import org.joda.time.DateTime;
import reactor.util.annotation.Nullable;

import javax.persistence.*;
import java.io.Serial;
import java.util.Objects;

// directly not used
@Entity
@Table(name = "activity")
public class Activity extends GuildEntity{
    @Serial
    private static final long serialVersionUID = -4910286185798200086L;

    @Column(name = "message_count")
    private int messageCount;

    @Column(name = "last_sent_message")
    private DateTime lastSentMessage;

    @Transient
    public void incrementMessageCount(){
        messageCount += 1;
    }

    public int messageCount(){
        return messageCount;
    }

    public void messageCount(int messageCount){
        this.messageCount = messageCount;
    }

    @Nullable
    public DateTime lastSentMessage(){
        return lastSentMessage;
    }

    public void lastSentMessage(DateTime lastSentMessage){
        this.lastSentMessage = Objects.requireNonNull(lastSentMessage, "lastSentMessage");
    }

    @Override
    public String toString(){
        return "Activity{" +
                "messageCount=" + messageCount +
                ", lastSentMessage=" + lastSentMessage +
                "} " + super.toString();
    }
}
