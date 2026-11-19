package dev.amatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import kotlinx.coroutines.delay

/**
 * Upstream sign-in phases (am_atv PhoneMusicSignInDialog). Authorization runs in
 * the phone's browser; the TV only mirrors progress. Expired/Failed offer retry.
 */
internal enum class PhoneSignInPhase { Starting, Waiting, Authorizing, Complete, Expired, Failed }

/**
 * Upstream-style TV dialog for the phone web sign-in flow: QR code for the
 * temporary pairing server opened by [PhoneMusicAuthorization]. All Apple
 * credentials stay in the phone browser; the TV only shows pairing state.
 */
@Composable
internal fun PhoneMusicSignInDialog(
    url: String?,
    phase: PhoneSignInPhase,
    errorMessage: String? = null,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    val retryable = phase == PhoneSignInPhase.Expired || phase == PhoneSignInPhase.Failed
    var autoClosed by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(retryable, phase) {
        withFrameNanos { }
        runCatching { primaryFocus.requestFocus() }
    }
    // Let users read the completed state before the dialog steps out of the way.
    LaunchedEffect(phase) {
        if (phase == PhoneSignInPhase.Complete && !autoClosed) {
            delay(1_600L)
            autoClosed = true
            onDismiss()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .width(820.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E))
                .padding(horizontal = 30.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneSignInQr(url.takeUnless { retryable || phase == PhoneSignInPhase.Complete }, phase)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.phone_sign_in_title),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.phone_sign_in_instructions),
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 14.sp,
                )
                Text(
                    text = stringResource(
                        when (phase) {
                            PhoneSignInPhase.Starting -> R.string.phone_sign_in_starting
                            PhoneSignInPhase.Waiting -> R.string.phone_sign_in_waiting
                            PhoneSignInPhase.Authorizing -> R.string.phone_sign_in_authorizing
                            PhoneSignInPhase.Complete -> R.string.phone_sign_in_complete
                            PhoneSignInPhase.Expired -> R.string.phone_sign_in_expired
                            PhoneSignInPhase.Failed -> R.string.phone_sign_in_failed
                        },
                    ),
                    color = if (retryable) Color(0xFFFFB0BA) else Color(0xFF9BE3A2),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (retryable && !errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                if (!url.isNullOrBlank() && !retryable && phase != PhoneSignInPhase.Complete) {
                    Text(
                        text = url,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (retryable) {
                        FocusSurface(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f).height(46.dp).focusRequester(primaryFocus),
                            cornerRadius = 23.dp,
                            background = Color.White.copy(alpha = 0.14f),
                            focusedBackground = Color.White.copy(alpha = 0.30f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.phone_sign_in_retry),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    FocusSurface(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .then(if (retryable) Modifier else Modifier.focusRequester(primaryFocus)),
                        cornerRadius = 23.dp,
                        background = Color.White.copy(alpha = 0.12f),
                        focusedBackground = Color.White.copy(alpha = 0.28f),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(
                                    if (phase == PhoneSignInPhase.Complete) R.string.phone_sign_in_done
                                    else R.string.phone_sign_in_cancel,
                                ),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** QR area: code while pairing, check on success, phone hint on retryable states. */
@Composable
private fun PhoneSignInQr(url: String?, phase: PhoneSignInPhase, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(226.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (url != null) Color.White else Color.White.copy(alpha = 0.06f))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            url != null -> QrCode(content = url, size = 206.dp, cornerRadius = 8.dp)
            phase == PhoneSignInPhase.Complete -> Icon(
                Icons.Rounded.CheckCircle, null,
                Modifier.size(64.dp), Color.White,
            )
            phase == PhoneSignInPhase.Expired || phase == PhoneSignInPhase.Failed -> Icon(
                Icons.Rounded.PhoneIphone, null,
                Modifier.size(64.dp), Color.White.copy(alpha = 0.35f),
            )
            else -> AppleLoadingIndicator(Modifier.size(28.dp))
        }
    }
}
