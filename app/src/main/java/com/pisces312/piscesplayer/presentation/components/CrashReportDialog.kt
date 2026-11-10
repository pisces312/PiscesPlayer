package com.pisces312.piscesplayer.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pisces312.piscesplayer.utils.CrashLogData
import androidx.compose.ui.res.stringResource
import com.pisces312.piscesplayer.R
import java.io.File

/**
 * Binder transactions are capped near 1MB, so a crash log carrying megabytes of diagnostic
 * output cannot be handed to the clipboard or to another app through Intent extras.
 * The clipboard payload is truncated; sharing goes through a FileProvider URI instead.
 */
private const val CLIPBOARD_PAYLOAD_LIMIT = 100_000
private const val CRASH_LOG_FILE_NAME = "crash-report.txt"

/**
 * Material3 Expressive styled dialog that displays crash information
 * and provides options to copy or share the crash log.
 */
@Composable
fun CrashReportDialog(
    crashLog: CrashLogData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fullLog = remember(crashLog) { crashLog.getFullLog() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.crash_report_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.crash_report_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.crash_report_date_line, crashLog.formattedDate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = stringResource(R.string.crash_report_error_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        
                        Text(
                            text = crashLog.exceptionMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.crash_report_stack_preview_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = crashLog.stackTrace.take(500) + if (crashLog.stackTrace.length > 500) "..." else "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val truncated = fullLog.length > CLIPBOARD_PAYLOAD_LIMIT
                            val payload = if (truncated) {
                                fullLog.take(CLIPBOARD_PAYLOAD_LIMIT)
                            } else {
                                fullLog
                            }
                            val clip = ClipData.newPlainText(
                                context.getString(R.string.crash_report_clipboard_label),
                                payload,
                            )
                            clipboard.setPrimaryClip(clip)
                            val message = if (truncated) {
                                context.getString(R.string.toast_crash_log_copied_truncated, payload.length)
                            } else {
                                context.getString(R.string.toast_crash_log_copied)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_copy))
                    }

                    FilledTonalButton(
                        onClick = {
                            // Share the log as a file: passing it through EXTRA_TEXT would blow the
                            // Binder transaction limit for the same reason the clipboard did.
                            runCatching {
                                val logFile = File(context.cacheDir, CRASH_LOG_FILE_NAME)
                                logFile.writeText(fullLog)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    logFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_report_share_subject))
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, context.getString(R.string.crash_report_share_chooser)),
                                )
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_crash_log_share_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}
