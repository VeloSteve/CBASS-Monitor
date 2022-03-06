# CBASS-Monitor
An Android app for monitoring CBASS systems.

Warning: this app is in a very rough state.  It is only public because I have a free account, but once it is ready you are free to use it.

This app will allow monitoring of temperature and schedule information during a CBASS run.  This is supported by the CBASS-R shield, or any system with Bluetooth Low Energy (BLE) capability.

Planned features:
- Selected from one or more CBASS systems in range.
- Monitor current temperatures in real time.
- Graph planned and actual temperatures, with some control over graph content and time span.
- Adjust the start time of an upcoming run, for example when sample preparation time is unpredictable.
- Set up a new experimental schedule.
- Download the full run log to the Android device.
- Manage logs on CBASS, for example saving a previous run and starting a new one in a clean log file.
-- The app should never delete data on CBASS.

Safety and security:
- We assume that no one is going to steal our data to publish before us, so there will be nothing to prevent unauthorized connections.
- Monitoring functions must never interfere with temperature control or the primary log file, so the size of data requests during an experiment must be limited.
- Modifying data could be disastrous, so no such functions will be implemented until a security strategy is in place.

For more information on CBASS see
Voolstra, C.R., Buitrago-López, C., Perna, G., Cárdenas, A., Hume, B.C.C., Rädecker, N., Barshis, D.J., 2020. Standardized short-term acute heat stress assays resolve historical differences in coral thermotolerance across microhabitat reef sites. Glob. Change Biol. 26, 4328–4343. https://doi.org/10.1111/gcb.15148

A methods paper with more details about building and using CBASS is in preparation.

