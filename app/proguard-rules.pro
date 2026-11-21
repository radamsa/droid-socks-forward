# No reflection is used, R8 can shrink everything freely.
# Keep the entry points that the platform instantiates by name.
-keep class dev.dav.socksforward.SocksForwardApp { *; }
-keep class dev.dav.socksforward.service.ProxyService { *; }
-keep class dev.dav.socksforward.ui.MainActivity { *; }
-keep class dev.dav.socksforward.ui.SettingsActivity { *; }
