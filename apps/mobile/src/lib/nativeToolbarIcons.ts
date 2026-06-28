import { Platform, type ImageSourcePropType } from "react-native";

const ANDROID_TOOLBAR_ICONS = {
  gitBranch: require("../assets/toolbar-icons/git-branch.xml"),
  moreVertical: require("../assets/toolbar-icons/more-vertical.xml"),
  terminal: require("../assets/toolbar-icons/terminal.xml"),
} as const satisfies Readonly<Record<string, ImageSourcePropType>>;

export function nativeToolbarIcon<const IosSymbol extends string>(
  iosSymbol: IosSymbol,
  androidIcon: keyof typeof ANDROID_TOOLBAR_ICONS,
): IosSymbol | ImageSourcePropType {
  return Platform.OS === "android" ? ANDROID_TOOLBAR_ICONS[androidIcon] : iosSymbol;
}
