package org.telegram.messenger;

/** JojoGram settings. Fill WORKER_URL once (no trailing slash). Empty = JojoGram features are off. */
public class JojoConfig {
    // Example: "https://jojogram-profiles.your-name.workers.dev"
    public static final String WORKER_URL = "";
    // Bot username WITHOUT @ (only needed when the worker runs in verified mode). Example: "JojoGramBot"
    public static final String BOT_USERNAME = "";

    public static boolean enabled() {
        return WORKER_URL != null && WORKER_URL.startsWith("https://");
    }
}
