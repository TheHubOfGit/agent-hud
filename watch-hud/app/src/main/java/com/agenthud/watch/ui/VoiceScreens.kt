package com.agenthud.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

sealed class VoiceUiState {
    data object Hidden : VoiceUiState()
    data class Recording(val targetLabel: String, val amplitude: Float, val seconds: Int) : VoiceUiState()
    data class Transcribing(val targetLabel: String) : VoiceUiState()
    data class Review(val targetLabel: String, val transcript: String) : VoiceUiState()
    data class Sending(val targetLabel: String) : VoiceUiState()
    data class Sent(val targetLabel: String) : VoiceUiState()
    data class Error(val message: String, val title: String = "Voice Error") : VoiceUiState()
}

@Composable
fun VoiceOverlay(
    state: VoiceUiState,
    onStopRecording: () -> Unit,
    onConfirmSend: (String) -> Unit,
    onCancel: () -> Unit
) {
    if (state is VoiceUiState.Hidden) return

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            is VoiceUiState.Recording -> {
                val sweep = (60f + state.amplitude * 290f).coerceIn(60f, 355f)
                PerimeterArc(sweepDegrees = sweep, color = ColorBlue, strokeDp = 4.5f)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onStopRecording() }
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "LISTENING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = ColorBlue,
                        maxLines = 1
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size((44 + (state.amplitude * 10)).dp)
                                .clip(CircleShape)
                                .background(Color(0xFF162C46)),
                            contentAlignment = Alignment.Center
                        ) {
                            MicIcon(color = ColorBlue, size = 20.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "0:${state.seconds.toString().padStart(2, '0')} · Tap to stop",
                            fontSize = 12.sp,
                            color = Color(0xFFDADCE0)
                        )
                    }
                    Text(
                        text = "Cancel",
                        fontSize = 11.sp,
                        color = ColorMuted,
                        modifier = Modifier.clickable { onCancel() }.padding(4.dp)
                    )
                }
            }

            is VoiceUiState.Transcribing -> {
                PerimeterArc(sweepDegrees = 180f, color = ColorBlue, strokeDp = 4f)
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Transcribing…", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(text = state.targetLabel, fontSize = 11.sp, color = ColorMuted)
                }
            }

            is VoiceUiState.Sending -> {
                PerimeterArc(sweepDegrees = 180f, color = ColorGreen, strokeDp = 4f)
                Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Sending…", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(text = state.targetLabel, fontSize = 11.sp, color = ColorMuted)
                }
            }

            is VoiceUiState.Sent -> {
                PerimeterArc(sweepDegrees = 360f, color = ColorGreen, strokeDp = 4f)
                Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CheckIcon(color = ColorGreen, size = 22.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(text = "Sent to ${state.targetLabel}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center)
                }
            }

            is VoiceUiState.Review -> {
                PerimeterArc(sweepDegrees = 320f, color = ColorGreen, strokeDp = 4f)
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SEND REPLY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = ColorGreen
                    )
                    Text(
                        text = "“${state.transcript}”",
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF202124))
                                .clickable { onCancel() },
                            contentAlignment = Alignment.Center
                        ) {
                            CloseIcon(color = ColorMuted, size = 11.dp)
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ColorGreen)
                                .clickable { onConfirmSend(state.transcript) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CheckIcon(color = Color.Black, size = 11.dp)
                            Text(text = "Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                        }
                    }
                }
            }

            is VoiceUiState.Error -> {
                PerimeterArc(sweepDegrees = 355f, color = ColorRed, strokeDp = 4f)
                Column(
                    modifier = Modifier.fillMaxSize().clickable { onCancel() }.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = state.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ColorRed)
                    Spacer(Modifier.height(6.dp))
                    Text(text = state.message, fontSize = 11.sp, color = ColorMuted, textAlign = TextAlign.Center, maxLines = 5)
                    Spacer(Modifier.height(10.dp))
                    Text(text = "Tap to dismiss", fontSize = 10.sp, color = Color(0xFF5F6368))
                }
            }

            VoiceUiState.Hidden -> Unit
        }
    }
}
