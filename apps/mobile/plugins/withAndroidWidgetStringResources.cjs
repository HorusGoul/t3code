const fs = require("node:fs");
const path = require("node:path");

const { withDangerousMod } = require("expo/config-plugins");

const toAndroidResourceName = (name) => {
  const resourceName = name
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1_$2")
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/[^A-Za-z0-9_]/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toLowerCase();

  return /^[a-z]/.test(resourceName) ? resourceName : `widget_${resourceName}`;
};

const escapeXmlSpecialChars = (value) =>
  value
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");

const createWidgetStringsXml = (widgets) => `<?xml version="1.0" encoding="utf-8"?>
<resources>
${widgets
  .map((widget) => {
    const resourceName = toAndroidResourceName(widget.name);

    return `  <string name="${resourceName}_display_name">${escapeXmlSpecialChars(
      widget.displayName,
    )}</string>
  <string name="${resourceName}_description">${escapeXmlSpecialChars(widget.description)}</string>`;
  })
  .join("\n")}
</resources>
`;

module.exports = function withAndroidWidgetStringResources(config, props = {}) {
  return withDangerousMod(config, [
    "android",
    (nextConfig) => {
      const widgets = Array.isArray(props.widgets) ? props.widgets : [];
      if (widgets.length === 0) {
        return nextConfig;
      }

      const valuesDirectory = path.join(
        nextConfig.modRequest.platformProjectRoot,
        "app/src/main/res/values",
      );

      fs.mkdirSync(valuesDirectory, { recursive: true });
      fs.writeFileSync(
        path.join(valuesDirectory, "expo_widgets.xml"),
        createWidgetStringsXml(widgets),
      );

      return nextConfig;
    },
  ]);
};
