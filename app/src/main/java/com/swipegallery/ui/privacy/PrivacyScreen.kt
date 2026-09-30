package com.swipegallery.ui.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.swipegallery.BuildConfig
import com.swipegallery.R
import com.swipegallery.ui.components.Divider
import com.swipegallery.ui.components.ListRow
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.ScreenHeading
import com.swipegallery.ui.components.SectionLabel
import com.swipegallery.ui.components.bottomSafeArea
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.openUrl

/** Plain-language summary of what the app does with data, plus configured legal links. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = SwipeTheme.colors
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = license != null) { license = null }

    Screen {
        Column(Modifier.fillMaxSize().topSafeArea().bottomSafeArea().readableWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (license != null) license = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = c.textPrimary)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter),
            ) {
                val shownLicense = license
                if (shownLicense != null) {
                    Text(shownLicense, style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                } else {
                    ScreenHeading(stringResource(R.string.privacy_title))
                    Spacer(Modifier.height(Space.xl))
                    listOf(
                        R.string.privacy_device_title to R.string.privacy_device_body,
                        R.string.privacy_access_title to R.string.privacy_access_body,
                        R.string.privacy_removal_title to R.string.privacy_removal_body,
                        R.string.privacy_purchases_title to R.string.privacy_purchases_body,
                        R.string.privacy_limits_title to R.string.privacy_limits_body,
                    ).forEach { (title, body) ->
                        SectionLabel(stringResource(title))
                        Spacer(Modifier.height(Space.s))
                        Text(stringResource(body), style = SwipeTheme.type.body, color = c.textPrimary)
                        Spacer(Modifier.height(Space.xl))
                    }
                    if (BuildConfig.PRIVACY_URL.isNotBlank()) {
                        ListRow(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            stringResource(R.string.link_privacy),
                            onClick = { context.openUrl(BuildConfig.PRIVACY_URL) },
                        )
                        Divider()
                    }
                    if (BuildConfig.TERMS_URL.isNotBlank()) {
                        ListRow(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            stringResource(R.string.link_terms),
                            onClick = { context.openUrl(BuildConfig.TERMS_URL) },
                        )
                        Divider()
                    }
                    ListRow(
                        Icons.Outlined.Description,
                        stringResource(R.string.privacy_font_licenses),
                        subtitle = stringResource(R.string.privacy_font_licenses_sub),
                        onClick = {
                            license = listOf("OFL-InstrumentSerif.txt", "OFL-Inter.txt").joinToString("\n\n————\n\n") { name ->
                                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                            }
                        },
                    )
                    Spacer(Modifier.height(Space.xxl))
                }
            }
        }
    }
}
