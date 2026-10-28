package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import io.github.snowdrit.mamokeep.R

/**
 * Имя машины с её силуэтом в заголовке экрана — и переключатель машин заодно.
 *
 * Живёт отдельно, потому что стоит на двух экранах сразу: «Машина» и «Журнал». Правило
 * «силуэт и имя — **одна** цель нажатия» должно меняться в одном месте: обе половины
 * ведут в один и тот же выбор машины, а два соседних отклика на одной строке человек
 * читает как два разных действия и ищет между ними разницу, которой нет.
 */
@Composable
fun CarTitle(name: String, bodyType: String?, silhouetteColor: Int?, onClick: () -> Unit) {
    val tint = silhouetteTint(silhouetteColor)
    val iconSize = dimensionResource(R.dimen.car_title_icon_size)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.car_title_spacing)),
        modifier = Modifier
            .clip(RoundedCornerShape(dimensionResource(R.dimen.car_title_corner_radius)))
            .clickable(
                onClickLabel = stringResource(R.string.home_switch_car),
                onClick = onClick,
            )
            .padding(
                horizontal = dimensionResource(R.dimen.car_title_padding_horizontal),
                vertical = dimensionResource(R.dimen.car_title_padding_vertical),
            ),
    ) {
        Icon(
            painter = rememberCarProfilePainter(bodyType, iconSize, iconSize),
            contentDescription = null,
            tint = tint,
            // У web-SVG, импортированного как drawable-nodpi PNG, intrinsic-размер —
            // исходные 1536 px. В заголовке он не должен диктовать ширину всей строки:
            // для векторных MDI здесь и раньше фактически получалось 24 dp.
            modifier = Modifier.size(iconSize),
        )
        Text(name)
    }
}
