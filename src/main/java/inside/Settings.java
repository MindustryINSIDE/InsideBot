package inside;

import discord4j.rest.util.Color;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Settings{
    @Value("${spring.application.token}")
    public String token;

    public String prefix = "$";

    public Color normalColor = Color.of(0xc4f5b7);

    public Color errorColor = Color.of(0xff3838);
}
