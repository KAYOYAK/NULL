package top.nullcmd.app

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.runtime.getValue

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import top.nullcmd.app.ui.about.AboutViewModel
import top.nullcmd.app.ui.about.AboutScreen
import top.nullcmd.app.ui.settings.SettingsViewModel
import top.nullcmd.app.ui.settings.SettingsScreen
import top.nullcmd.app.ui.models.ModelManagerScreen
import top.nullcmd.app.ui.models.ModelManagerViewModel

@Composable
fun MainNavigation(start: NavKey = Models) {
  // 从网页前端进原生界面时，栈从那一页开始：返回就退回对话。
  val backStack = rememberNavBackStack(start)

  // The engine and the download coordinator belong to the Application, not to
  // this graph: a loaded model occupies hundreds of megabytes to several
  // gigabytes, and reaching either must not depend on a screen staying composed.
  val container = (LocalContext.current.applicationContext as DemoApplication).container

  NavDisplay(
    backStack = backStack,
    onBack = {
      // Bounded, for the same reason as the history screen's open callback: the
      // library refuses an empty stack, and at one entry back means leaving the
      // app, which the library's own handler already does by finishing the
      // activity.
      if (backStack.size > 1) backStack.removeLastOrNull()
    },
    entryProvider =
      entryProvider {
        entry<Settings> {
          val settingsViewModel: SettingsViewModel =
            viewModel {
              SettingsViewModel(
                settingsStore = container.settingsStore,
                engine = container.llmEngine,
              )
            }

          SettingsScreen(
            viewModel = settingsViewModel,
            modifier = Modifier.safeDrawingPadding(),
          )
        }

        entry<Models> {
          val manager: ModelManagerViewModel =
            viewModel {
              ModelManagerViewModel(
                store = container.modelStore,
                coordinator = container.downloadCoordinator,
                settingsStore = container.settingsStore,
                engine = container.llmEngine,
                registry = container.modelRegistry,
                benchmarkLog = container.benchmarkLog,
                openPicked = container.openPickedFile,
                importer = container.modelImporter,
              )
            }

          // Re-read the device on the way out, so coming back -- including after
          // the app has been in the background -- shows what is actually there.
          // The screen only watches the download coordinator, which knows nothing
          // about a file that changed for any other reason.
          DisposableEffect(Unit) {
            onDispose { manager.refresh() }
          }

          ModelManagerScreen(
            viewModel = manager,
            onOpenAbout = { if (backStack.lastOrNull() != About) backStack.add(About) },
            modifier = Modifier.safeDrawingPadding().padding(16.dp),
          )
        }

        entry<About> {
          // The app's own directory, for the parts no store owns: the crash log and
          // the runtime's scratch space. Erasing has to reach them, and this is the
          // only layer with a Context.
          val appFilesDir = LocalContext.current.filesDir
          val about: AboutViewModel =
            viewModel {
              AboutViewModel(
                history = container.webHistory,
                modelStore = container.modelStore,
                coordinator = container.downloadCoordinator,
                settingsStore = container.settingsStore,
                registry = container.modelRegistry,
                engine = container.llmEngine,
                appFilesDir = appFilesDir,
              )
            }

          // Re-read on the way out, so coming back after a download or a chat
          // shows what is actually held rather than what was held last time.
          DisposableEffect(Unit) {
            onDispose { about.refresh() }
          }

          AboutScreen(
            viewModel = about,
            modifier = Modifier.safeDrawingPadding(),
          )
        }
      },
  )
}