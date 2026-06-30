package expo.modules.t3nativecontrols

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class T3NativeControlsModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("T3NativeControls")

    AsyncFunction("startAgentActivityForegroundServiceAsync") {
        fallbackTitle: String,
        fallbackBody: String,
        fallbackChipText: String,
        notificationsJson: String,
      ->
      T3AgentActivityForegroundService.start(
        requireReactContext(),
        fallbackTitle,
        fallbackBody,
        fallbackChipText,
        notificationsJson,
      )
    }

    AsyncFunction("updateAgentActivityForegroundServiceAsync") {
        fallbackTitle: String,
        fallbackBody: String,
        fallbackChipText: String,
        notificationsJson: String,
      ->
      T3AgentActivityForegroundService.start(
        requireReactContext(),
        fallbackTitle,
        fallbackBody,
        fallbackChipText,
        notificationsJson,
      )
    }

    AsyncFunction("stopAgentActivityForegroundServiceAsync") {
      T3AgentActivityForegroundService.stop(requireReactContext())
    }

    View(T3HeaderButtonView::class) {
      Prop("label") { view: T3HeaderButtonView, label: String ->
        view.setLabel(label)
      }
      Prop("systemImage") { view: T3HeaderButtonView, systemImage: String ->
        view.setSystemImage(systemImage)
      }

      Events("onTriggered")
    }
  }

  private fun requireReactContext() =
    appContext.reactContext ?: throw IllegalStateException("App context is not available")
}
