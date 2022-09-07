package inside.command.common;

import discord4j.core.spec.MessageEditSpec;
import inside.command.Command;
import inside.command.CommandEnvironment;
import inside.command.CommandInteraction;
import inside.command.DiscordCommand;
import inside.service.MessageService;
import org.reactivestreams.Publisher;

@DiscordCommand(key = "commands.ping.key", description = "commands.ping.desc")
public class PingCommand extends Command {

    public PingCommand(MessageService messageService) {
        super(messageService);
    }

    @Override
    public Publisher<?> execute(CommandEnvironment env, CommandInteraction interaction) {
        long start = System.currentTimeMillis();
        return env.channel().createMessage(messageService.get(null,"inside.static.wait"))
                .flatMap(message -> message.edit(MessageEditSpec.builder()
                        .contentOrNull(String.format(messageService.get(null,"commands.ping.message"), System.currentTimeMillis() - start))
                        .build()))
                .then();
    }
}