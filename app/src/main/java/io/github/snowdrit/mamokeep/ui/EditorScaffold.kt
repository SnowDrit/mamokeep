package io.github.snowdrit.mamokeep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.snowdrit.mamokeep.R

/**
 * Каркас редактора, живущего на своём экране, а не в диалоге.
 *
 * Зачем вообще экран. `AlertDialog` не может быть выше окна, поэтому его содержимое
 * прокручивается внутри маленькой коробки: поля тесно, клавиатура наезжает, длинные
 * подписи обрываются. Пока полей было три, это сходило с рук; у позиции их девять.
 *
 * Нижняя панель вкладок на время правки убирается — и это не про красоту. Пока она
 * висит, нажатие на «Журнал» посреди заполнения молча выбрасывает введённое. Экран
 * редактора это задача с началом и концом, и уйти из неё можно только сохранив или
 * отменив.
 *
 * `BackHandler` стоит здесь, а не в `MainActivity`: он глубже в дереве и потому
 * перехватывает «назад» раньше — редактор закрывается, а не вкладка переключается.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScaffold(
    title: String,
    onClose: () -> Unit,
    /** `null` — сохранять пока нечего: кнопка видна, но не нажимается. */
    onSave: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Подпись действия. У поездки это не «Сохранить», а «Прибавить к пробегу». */
    saveLabel: String = stringResource(R.string.common_save),
    saveModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { onClose() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        // Отступы под системные панели считает `MainActivity`.
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.common_cancel),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onSave?.invoke() }, enabled = onSave != null, modifier = saveModifier) {
                        Text(saveLabel)
                    }
                },
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
