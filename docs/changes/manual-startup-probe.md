Manual Biome version collection now runs during SDK server startup, with a five-second deadline after synchronous target preparation. Cancellation, project disposal and stopping the server terminate the owned probe. Invalid output and nonzero exits fail manual startup. Automatic mode continues to read package metadata.

The version probe and proxy run in the selected workspace directory, including Manual v1 with an explicit config override in another content root.

The deadline covers collecting the version process result. SDK target preparation and final proxy creation remain synchronous and have no new wall-clock deadline. If a stop overlaps final process creation, the descriptor retains and terminates the returned handler before handing it to the SDK.

This change builds on shared-daemon ownership. Automatic and Manual proxies preserve a daemon used by another project, including cancellation before SDK handoff. Version probes retain recursive cleanup.

Linux regression coverage includes manual v1/v2 arguments and working directories, coroutine and platform cancellation, process descendants, stop during the probe, stop during final process creation, and the process-exit observation race. The required inventory includes shared-daemon and highlighting controls. Windows and WSL were not exercised.
