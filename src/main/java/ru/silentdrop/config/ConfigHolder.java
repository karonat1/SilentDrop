package ru.silentdrop.config;

/**
 * Holder of the current configuration snapshot. A reload (/sd reload) simply swaps
 * the reference, so every listener immediately sees the new values without
 * re-registering events.
 */
public final class ConfigHolder {

    private volatile Settings settings;
    private volatile Messages messages;

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public void update(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }
}
