# Manual action context

Apply Safe Fixes and Sort Imports are offered only for a writable, supported file in a live editor while Biome integration is enabled. Direct invocation checks the same context; unsupported files retain the existing explanation and settings link. Presentation updates run on the background thread.

The six `BiomeManualActionContextTest` cases cover a supported editor, a missing or disposed editor, a read-only document, disabled integration, and an unsupported file. Run them with `./gradlew test --tests '*BiomeManualActionContextTest'`.
