Opening a file before a Biome config exists now recovers diagnostics and formatting when the config is created or repaired. The existing editor stays open. This works in Automatic mode and in Manual mode when no config override is set; Manual mode keeps the selected executable.

Recovery uses the platform's start-if-needed API and leaves running servers in place. Explicit config overrides and Disabled mode prevent discovery. Queued work rechecks discovery settings and project disposal. The platform starts from the current open editors and executable selection.
