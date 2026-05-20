package org.onekash.kashcal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R

@Composable
fun KashCalTopAppBarTitle(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
    )
}
