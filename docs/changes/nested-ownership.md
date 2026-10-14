Opening files in an independently configured nested workspace selects that workspace's Biome server and formatter rules. Existing parent and child servers retain their ownership while a config is temporarily malformed or missing.

Public Restart restores both workspaces for either open-file order without reopening their editors. Each process starts in its selected workspace directory. Explicit manual configuration continues to own the whole project root.

Creating an independent nested config beneath a running parent, or repairing a child config after restarting while it was malformed, still requires manual recovery.
