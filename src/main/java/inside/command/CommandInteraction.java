package inside.command;

import inside.util.Try;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CommandInteraction {
    private final String commandName;
    private final List<CommandOption> options;

    public CommandInteraction(String commandName, List<CommandOption> options) {
        this.commandName = Objects.requireNonNull(commandName);
        this.options = Objects.requireNonNull(options);
    }

    public String getCommandName() {
        return commandName;
    }

    public List<CommandOption> getOptions() {
        return options;
    }

    public Optional<CommandOption> getOption(int index) {
        return Try.ofCallable(() -> options.get(index)).toOptional();
    }

    public Optional<CommandOption> getOption(String name) {
        Objects.requireNonNull(name, "name");
        return options.stream()
                .filter(option -> option.getName().equals(name))
                .findFirst();
    }

    @Override
    public String toString() {
        return "CommandInteraction{" +
                "commandName='" + commandName + '\'' +
                ", options=" + options +
                '}';
    }
}
