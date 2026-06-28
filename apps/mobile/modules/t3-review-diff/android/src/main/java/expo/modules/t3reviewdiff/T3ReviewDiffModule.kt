package expo.modules.t3reviewdiff

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class T3ReviewDiffModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("T3ReviewDiffSurface")

    View(T3ReviewDiffView::class) {
      Prop("rowsJson") { view: T3ReviewDiffView, rowsJson: String ->
        view.setRowsJson(rowsJson)
      }

      Prop("tokensJson") { view: T3ReviewDiffView, tokensJson: String ->
        view.setTokensJson(tokensJson)
      }

      Prop("tokensPatchJson") { view: T3ReviewDiffView, tokensPatchJson: String ->
        view.setTokensPatchJson(tokensPatchJson)
      }

      Prop("tokensResetKey") { view: T3ReviewDiffView, tokensResetKey: String ->
        view.setTokensResetKey(tokensResetKey)
      }

      Prop("collapsedFileIdsJson") { view: T3ReviewDiffView, collapsedFileIdsJson: String ->
        view.setCollapsedFileIdsJson(collapsedFileIdsJson)
      }

      Prop("viewedFileIdsJson") { view: T3ReviewDiffView, viewedFileIdsJson: String ->
        view.setViewedFileIdsJson(viewedFileIdsJson)
      }

      Prop("selectedRowIdsJson") { view: T3ReviewDiffView, selectedRowIdsJson: String ->
        view.setSelectedRowIdsJson(selectedRowIdsJson)
      }

      Prop("collapsedCommentIdsJson") { view: T3ReviewDiffView, collapsedCommentIdsJson: String ->
        view.setCollapsedCommentIdsJson(collapsedCommentIdsJson)
      }

      Prop("appearanceScheme") { view: T3ReviewDiffView, appearanceScheme: String ->
        view.setAppearanceScheme(appearanceScheme)
      }

      Prop("themeJson") { view: T3ReviewDiffView, themeJson: String ->
        view.setThemeJson(themeJson)
      }

      Prop("styleJson") { view: T3ReviewDiffView, styleJson: String ->
        view.setStyleJson(styleJson)
      }

      Prop("rowHeight") { view: T3ReviewDiffView, rowHeight: Double ->
        view.setRowHeight(rowHeight)
      }

      Prop("contentWidth") { view: T3ReviewDiffView, contentWidth: Double ->
        view.setContentWidth(contentWidth)
      }

      Prop("initialRowIndex") { view: T3ReviewDiffView, initialRowIndex: Double ->
        view.setInitialRowIndex(initialRowIndex)
      }

      Events("onDebug", "onToggleFile", "onToggleViewedFile", "onPressLine", "onToggleComment")
    }
  }
}
