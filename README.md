# Rei

**Rei** is a modern, multi-backend root manager for Android, supporting both **KernelSU** and **APatch**.

It provides a unified interface to manage root access, modules, and advanced system tools, regardless of the underlying root implementation.

## Features

*   **Multi-Backend Support**: automatically detects and manages KernelSU (ksud) or APatch (SuperKey).
*   **App Access Control**:
    *   Grant or deny root access per app.
    *   **Exclude Module Modifications**: unmount modules for specific apps (non-root isolation) to bypass detection.
*   **Module Management**: install, update, and manage standard modules (Magisk/KSU/APatch compatible).
*   **Boot & Partition Tools**:
    *   **Patch Boot Image**: easy patching for KernelPatch/APatch installation.
    *   **Partition Manager**: backup, flash, and manage partitions; supports A/B slot switching.
*   **Murasaki & HymoFS Integration**: supports advanced hiding and stealth features via Murasaki API and HymoFS.
*   **Modern UI**: built with Jetpack Compose and Material 3 design.

## Credits & Acknowledgements

Rei stands on the shoulders of giants. We would like to express our sincere gratitude to the following projects and their developers:

*   **[KernelSU](https://github.com/tiann/KernelSU)** by [weishu](https://github.com/tiann) - For the revolutionary kernel-based root solution.
*   **[APatch](https://github.com/bmax121/APatch)** by [bmax121](https://github.com/bmax121) - For the innovative kernel patching method and KPM architecture.
*   **[SukiSU](https://github.com/SukiSU-Ultra)** - For inspiration and contributions to the ecosystem.
*   **[Magisk](https://github.com/topjohnwu/Magisk)** & **[libsu](https://github.com/topjohnwu/libsu)** by [topjohnwu](https://github.com/topjohnwu) - The foundation of modern Android root and module system.
*   **Murasaki API** & **HymoFS** - For the advanced stealth and isolation capabilities.

## License

This project is licensed under the applicable open source license (see `LICENSE` file for details).
