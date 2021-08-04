package inside.event.audit;

import discord4j.common.util.Snowflake;
import discord4j.core.object.Embed;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.*;
import inside.data.entity.AuditAction;
import inside.data.entity.base.NamedReference;
import inside.util.*;
import org.joda.time.format.*;
import reactor.util.context.ContextView;

import static inside.event.audit.Attribute.*;
import static inside.util.ContextUtil.*;

public class AuditProviders{

    private AuditProviders(){}

    @ForwardAuditProvider(AuditActionType.MESSAGE_EDIT)
    public static class MessageEditAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            Snowflake messageId = action.getAttribute(MESSAGE_ID);
            String oldContent = action.getAttribute(OLD_CONTENT);
            String newContent = action.getAttribute(NEW_CONTENT);
            String url = action.getAttribute(AVATAR_URL);
            if(messageId == null || oldContent == null || newContent == null || url == null){
                return;
            }

            embed.setAuthor(action.user().name(), null, url);
            embed.setDescription(messageService.format(context, "audit.message.edit.description",
                    action.guildId().asString(),
                    action.channel().id(),
                    messageId.asString()));

            if(oldContent.length() > 0){
                embed.addField(messageService.get(context, "audit.message.old-content.title"),
                        MessageUtil.substringTo(oldContent, Embed.Field.MAX_VALUE_LENGTH), false);
            }

            if(newContent.length() > 0){
                embed.addField(messageService.get(context, "audit.message.new-content.title"),
                        MessageUtil.substringTo(newContent, Embed.Field.MAX_VALUE_LENGTH), true);
            }

            embed.addField(messageService.get(context, "audit.message.channel"),
                    getChannelReference(context, action.channel()), false);

            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.MESSAGE_DELETE)
    public static class MessageDeleteAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            String oldContent = action.getAttribute(OLD_CONTENT);
            String url = action.getAttribute(AVATAR_URL);
            NamedReference target = action.target();
            if(oldContent == null || url == null || target == null){
                return;
            }

            embed.setAuthor(target.name(), null, url);

            if(oldContent.length() > 0){
                embed.addField(messageService.get(context, "audit.message.deleted-content.title"),
                        MessageUtil.substringTo(oldContent, Embed.Field.MAX_VALUE_LENGTH), true);
            }

            embed.addField(messageService.get(context, "audit.message.channel"),
                    getChannelReference(context, action.channel()), false);

            if(!action.user().equals(target)){
                embed.addField(messageService.get(context, "audit.message.responsible-user"),
                        getUserReference(context, action.user()), false);
            }

            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.MESSAGE_CLEAR)
    public static class MessageClearAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            Long count = action.getAttribute(COUNT);
            if(count == null){
                return;
            }

            embed.setDescription(messageService.format(context, "audit.message.clear.description", count,
                    messageService.getCount(context, "common.plurals.message", count)));
            embed.addField(messageService.get(context, "audit.member.admin"),
                    getUserReference(context, action.user()), true);
            embed.addField(messageService.get(context, "audit.message.channel"),
                    getChannelReference(context, action.channel()), false);
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.USER_JOIN)
    public static class MemberJoinAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            embed.setDescription(messageService.format(context, "audit.member.join.description",
                    getUserReference(context, action.user())));
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.USER_LEAVE)
    public static class MemberLeaveAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            embed.setDescription(messageService.format(context, "audit.member.leave.description",
                    getUserReference(context, action.user())));
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.USER_KICK)
    public static class MemberKickAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            String reason = action.getAttribute(REASON);
            NamedReference target = action.target();
            if(target == null || reason == null){
                return;
            }

            embed.setDescription(messageService.format(context, "audit.member.kick.title", getUserReference(context, target)));
            embed.addField(messageService.get(context, "audit.member.admin"),
                    getUserReference(context, action.user()), true);
            embed.addField(messageService.get(context, "audit.member.reason"), reason, true);
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.USER_BAN)
    public static class MemberBanAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            String reason = action.getAttribute(REASON);
            NamedReference target = action.target();
            if(target == null || reason == null){
                return;
            }

            embed.setDescription(messageService.format(context, "audit.member.ban.title", getUserReference(context, target)));
            embed.addField(messageService.get(context, "audit.member.admin"),
                    getUserReference(context, action.user()), true);
            embed.addField(messageService.get(context, "audit.member.reason"), reason, true);
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.USER_UNMUTE)
    public static class MemberUnmuteAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            NamedReference target = action.target();
            if(target == null){
                return;
            }

            embed.setDescription(messageService.format(context, "audit.member.unmute.title", getUserReference(context, target)));
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.USER_MUTE)
    public static class MemberMuteAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            Long delay = action.getAttribute(DELAY);
            String reason = action.getAttribute(REASON);
            NamedReference target = action.target();
            if(reason == null){
                reason = messageService.get(context, "common.not-defined");
            }

            if(delay == null || target == null){
                return;
            }

            DateTimeFormatter formatter = DateTimeFormat.shortDateTime()
                    .withLocale(context.get(KEY_LOCALE))
                    .withZone(context.get(KEY_TIMEZONE));

            embed.setDescription(messageService.format(context, "audit.member.mute.title", getUserReference(context, target)));
            embed.addField(messageService.get(context, "audit.member.admin"),
                    getUserReference(context, action.user()), true);
            embed.addField(messageService.get(context, "audit.member.reason"), reason, true);
            embed.addField(messageService.get(context, "audit.member.mute.delay"), formatter.print(delay), true);
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.REACTION_ADD)
    public static class ReactionAddAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            Snowflake messageId = action.getAttribute(MESSAGE_ID);
            ReactionEmoji emoji = action.getAttribute(REACTION_EMOJI);
            if(messageId == null || emoji == null){
                return;
            }

            embed.setDescription(messageService.format(context, "audit.reaction.add.description",
                    getUserReference(context, action.user()), DiscordUtil.getEmoji(emoji),
                    action.guildId().asString(), action.channel().id(), messageId.asString()));

            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.REACTION_REMOVE)
    public static class ReactionRemoveAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){

        }
    }

    @ForwardAuditProvider(AuditActionType.REACTION_REMOVE_ALL)
    public static class ReactionRemoveAllAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){

        }
    }

    @ForwardAuditProvider(AuditActionType.VOICE_JOIN)
    public static class VoiceJoinAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            embed.setDescription(messageService.format(context, "audit.voice.join.description",
                    getUserReference(context, action.user()), getShortReference(context, action.channel())));
            addTimestamp(context, action, embed);
        }
    }

    @ForwardAuditProvider(AuditActionType.VOICE_LEAVE)
    public static class VoiceLeaveAuditProvider extends BaseAuditProvider{
        @Override
        protected void build(AuditAction action, ContextView context, MessageCreateSpec spec, EmbedCreateSpec embed){
            embed.setDescription(messageService.format(context, "audit.voice.leave.description",
                    getUserReference(context, action.user()), getShortReference(context, action.channel())));
            addTimestamp(context, action, embed);
        }
    }
}
