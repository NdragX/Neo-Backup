package com.machiav3lli.backup.ui.pages

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo // Import ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.lang.StringBuilder

// Data class to hold app and activity information
data class AppActivityDetails(
    val appName: String,
    val packageName: String,
    val activities: List<ActivityInfo>,
    val isEnabled: Boolean,
    val isSystemApp: Boolean,
    val sourceDir: String // Add sourceDir
)

// Enhanced executeRootCommand
private fun executeRootCommand(command: String): Pair<Boolean, String> {
    try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = StringBuilder()
        // Read stdout
        val stdoutReader = process.inputStream.bufferedReader()
        var line: String?
        while (stdoutReader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
        stdoutReader.close() // Close stream
        // Read stderr
        val stderrReader = process.errorStream.bufferedReader()
        while (stderrReader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
        stderrReader.close() // Close stream
        val exitCode = process.waitFor()
        return Pair(exitCode == 0, output.toString().trim())
    } catch (e: Exception) {
        e.printStackTrace()
        return Pair(false, e.message ?: "Unknown error executing root command")
    }
}

@Composable
fun ConfirmationDialog(
    showDialog: MutableState<Boolean>,
    title: String,
    message: String,
    onConfirm: () -> Unit
) {
    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = {
                    showDialog.value = false
                    onConfirm()
                }) { Text("Confirm") }
            },
            dismissButton = {
                Button(onClick = { showDialog.value = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActivitiesPage() {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var appActivitiesList by remember { mutableStateOf<List<MutableState<AppActivityDetails>>>(emptyList()) }

    LaunchedEffect(Unit) {
        val packages = packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS) // Ensure flags for sourceDir
        appActivitiesList = packages.mapNotNull { packageInfo ->
            val appInfo = packageInfo.applicationInfo
            if (appInfo == null) {
                return@mapNotNull null // Skip if applicationInfo is null
            }
            mutableStateOf(
                AppActivityDetails(
                    appName = appInfo.loadLabel(packageManager)?.toString() ?: packageInfo.packageName, // Provide fallback for appName
                    packageName = packageInfo.packageName,
                    activities = packageInfo.activities?.toList() ?: emptyList(),
                    isEnabled = appInfo.enabled,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    sourceDir = appInfo.sourceDir ?: ""
                )
            )
        }.sortedBy { it.value.appName }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("App Activities") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(8.dp)
        ) {
            items(appActivitiesList) { appDetailsState ->
                AppActivityItem(appDetailsState = appDetailsState)
            }
        }
    }
}

@Composable
fun AppActivityItem(appDetailsState: MutableState<AppActivityDetails>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val appDetails = appDetailsState.value

    val showConvertToSystemDialog = remember { mutableStateOf(false) }
    val showConvertToUserDialog = remember { mutableStateOf(false) }

    ConfirmationDialog(
        showDialog = showConvertToSystemDialog,
        title = "Confirm Conversion to System App",
        message = stringResource(id = com.machiav3lli.backup.R.string.convert_system_app_warning),
        onConfirm = {
            if (appDetails.sourceDir.isBlank()) {
                Toast.makeText(context, "Source directory not found.", Toast.LENGTH_SHORT).show()
                return@ConfirmationDialog
            }
            val targetPackageName = appDetails.packageName
            // Sanitize package name to be safe as a directory name, although usually it is.
            val safePackageNameDir = targetPackageName.replace(Regex("[^a-zA-Z0-9._-]"), "")
            val systemAppDir = "/system/app/$safePackageNameDir"
            val systemApkPath = "$systemAppDir/$safePackageNameDir.apk" // Use sanitized name for APK as well

            var success = false
            var msg: String

            var (remountRwSuccess, remountRwOutput) = executeRootCommand("mount -o rw,remount /system")
            if (!remountRwSuccess) {
                msg = "Failed to remount /system rw: $remountRwOutput"
            } else {
                val (mkdirSuccess, mkdirOutput) = executeRootCommand("mkdir -p $systemAppDir")
                if (!mkdirSuccess) {
                    msg = "Failed to create $systemAppDir: $mkdirOutput"
                } else {
                    val (cpSuccess, cpOutput) = executeRootCommand("cp -a \"${appDetails.sourceDir}\" \"$systemApkPath\"")
                    if (!cpSuccess) {
                        msg = "Failed to copy APK: $cpOutput\nFrom: ${appDetails.sourceDir}\nTo: $systemApkPath"
                    } else {
                        executeRootCommand("chmod 755 \"$systemAppDir\"")
                        executeRootCommand("chmod 644 \"$systemApkPath\"")
                        // Optional chown commands (usually not needed if /system/app has correct default ownership/context)
                        // executeRootCommand("chown system:system \"$systemAppDir\"")
                        // executeRootCommand("chown system:system \"$systemApkPath\"")

                        val (remountRoSuccess, remountRoOutput) = executeRootCommand("mount -o ro,remount /system")
                        if(!remountRoSuccess) {
                            msg = "Copied to system, but failed to remount /system ro: $remountRoOutput. REBOOT RECOMMENDED."
                            success = true // Partial success
                        } else {
                            msg = "${appDetails.appName} converted to system app. Reboot recommended."
                            success = true
                        }
                    }
                }
                if (!success && remountRwSuccess) { // If any step failed after remounting rw, try to remount ro
                     executeRootCommand("mount -o ro,remount /system")
                }
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            if (success) {
                appDetailsState.value = appDetails.copy(isSystemApp = true)
            }
        }
    )

    ConfirmationDialog(
        showDialog = showConvertToUserDialog,
        title = "Confirm Conversion to User App",
        message = stringResource(id = com.machiav3lli.backup.R.string.convert_user_app_warning),
        onConfirm = {
            val targetPackageName = appDetails.packageName
            val safePackageNameDir = targetPackageName.replace(Regex("[^a-zA-Z0-9._-]"), "")
            val systemAppDirs = listOf("/system/app/$safePackageNameDir", "/system/priv-app/$safePackageNameDir")
            var foundAndRemoved = false
            var errorMsg = ""
            var finalMsg: String

            val (remountRwSuccess, remountRwOutput) = executeRootCommand("mount -o rw,remount /system")
            if (!remountRwSuccess) {
                finalMsg = "Failed to remount /system rw: $remountRwOutput"
            } else {
                for (dirToRemove in systemAppDirs) {
                    val (checkExistsSuccess, _) = executeRootCommand("[ -d \"$dirToRemove\" ]")
                    if (checkExistsSuccess) {
                        val (rmSuccess, rmOutput) = executeRootCommand("rm -rf \"$dirToRemove\"")
                        if (rmSuccess) {
                            foundAndRemoved = true
                            break
                        } else {
                            errorMsg += "Failed to remove $dirToRemove: $rmOutput\n"
                        }
                    }
                }

                if (!foundAndRemoved && errorMsg.isBlank()) {
                     errorMsg = "${appDetails.appName} not found in typical system app locations (${systemAppDirs.joinToString()}).\n"
                }

                val (remountRoSuccess, remountRoOutput) = executeRootCommand("mount -o ro,remount /system")
                if (!remountRoSuccess) {
                     errorMsg += "Action complete (or failed), but also failed to remount /system ro: $remountRoOutput. REBOOT RECOMMENDED."
                }

                finalMsg = if (foundAndRemoved) {
                    "${appDetails.appName} removed from system. ${errorMsg}Reboot and reinstall as user app recommended."
                } else {
                    "Failed to convert to user app. ${errorMsg}"
                }
            }

            Toast.makeText(context, finalMsg, Toast.LENGTH_LONG).show()
            if (foundAndRemoved) {
                appDetailsState.value = appDetails.copy(isSystemApp = false, isEnabled = false)
            }
        }
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = appDetails.appName, style = MaterialTheme.typography.titleMedium)
            Text(text = appDetails.packageName, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isSelfPackageButtons = appDetails.packageName == context.packageName
                 if (appDetails.isEnabled) {
                    Button(
                        onClick = {
                            val (disableSuccess, disableOutput) = executeRootCommand("pm disable ${appDetails.packageName}")
                            if (isSelfPackageButtons && !appDetails.isSystemApp) { // User app trying to disable self
                                 Toast.makeText(context, "Cannot disable self via this button.", Toast.LENGTH_SHORT).show()
                            } else if (isSelfPackageButtons && appDetails.isSystemApp) { // System app trying to disable self
                                 Toast.makeText(context, "Cannot disable self (system app) via this button. Convert to user app first.", Toast.LENGTH_LONG).show()
                            } else if (disableSuccess) {
                                appDetailsState.value = appDetails.copy(isEnabled = false)
                                Toast.makeText(context, "${appDetails.appName} disabled", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to disable ${appDetails.appName}: $disableOutput", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        enabled = !(isSelfPackageButtons && !appDetails.isSystemApp)
                    ) { Text("Disable App") }
                } else {
                    Button(onClick = {
                        val (enableSuccess, enableOutput) = executeRootCommand("pm enable ${appDetails.packageName}")
                        if (enableSuccess) {
                            appDetailsState.value = appDetails.copy(isEnabled = true)
                            Toast.makeText(context, "${appDetails.appName} enabled", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to enable ${appDetails.appName}: $enableOutput", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Enable App") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isSelfPackageConvert = appDetails.packageName == context.packageName
                if (appDetails.isSystemApp) {
                    Button(
                        onClick = { showConvertToUserDialog.value = true },
                        enabled = !isSelfPackageConvert
                    ) { Text(stringResource(id = com.machiav3lli.backup.R.string.convert_to_user_app)) }
                } else {
                    Button(
                        onClick = { showConvertToSystemDialog.value = true },
                        enabled = !isSelfPackageConvert
                    ) { Text(stringResource(id = com.machiav3lli.backup.R.string.convert_to_system_app)) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (appDetails.activities.isNotEmpty() || expanded) {
                Text(
                    text = if (expanded) "Hide Activities (${appDetails.activities.size})" else "Show Activities (${appDetails.activities.size})",
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (expanded) {
                if (appDetails.activities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    appDetails.activities.forEach { activityInfo ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = activityInfo.name.substringAfterLast('.'),
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Button(onClick = {
                                try {
                                    val intent = Intent().apply {
                                        component = ComponentName(appDetails.packageName, activityInfo.name)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error launching activity: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Launch") }
                        }
                    }
                } else {
                    Text("No launchable activities found.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
