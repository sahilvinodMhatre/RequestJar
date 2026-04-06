# RequestJar — Burp Suite Extension

> **Save, organize, and replay HTTP requests during penetration testing — right inside Burp Suite.**

[![Download RequestJar JAR](https://img.shields.io/badge/Download-Release%20JAR-blue?style=for-the-badge&logo=java)](https://github.com/sahilvinodMhatre/RequestJar/releases/download/RequestJar-V1.0.0/RequestJar-1.0.0.jar)

---

![RequestJar Interface](Screenshots/interface.png)

## ✨ Features

| Feature | Description |
|---|---| 
| 🕸️ **Collections** | Top-level collections (one per target) with unlimited subfolders |
| 💾 **Save Requests** | Right-click any request in any Burp tab → Send to RequestJar |
| 📁 **Folder Creation** | Create collections & subfolders directly from the save dialog |
| 🎨 **Syntax Highlighting** | Requests displayed with colour-coded HTTP syntax |
| 📤 **Export** | Export one collection or all collections to JSON (full hierarchy) or CSV |
| 📥 **Import** | Import a previously exported JSON file to restore full folder structure |
| 🔁 **Burp Integration** | Send stored requests to **Repeater**, **Intruder**, **Comparer**, or **Scanner** |
| 🗄️ **Local SQLite DB** | All data stays local — no external connections |

---

## 🚀 Installation (Recommended — Pre-built JAR)

No build tools required.

1. Go to the [**Releases**](../../releases) page and download **`RequestJar-1.0.0.jar`**.
2. Open **Burp Suite**.
3. Navigate to **Extensions** → **Installed** → **Add**.
4. Set **Extension type** to `Java`.
5. Click **Select file** and choose the downloaded `RequestJar-1.0.0.jar`.
6. Click **Next** — the **RequestJar** tab will appear in Burp's tab bar.

> **Note:** The JAR is self-contained (all dependencies bundled). No separate installation steps needed.

---


## 📖 Usage

### Saving a Request

1. In any Burp tab (Proxy, Repeater, etc.), **right-click** a request.
2. Select **Send to RequestJar**.
3. In the dialog that opens:
   - Select an existing collection or subfolder, **or**
   - Click **📂 New Collection** to create a new one, **or**
   - Select a collection and click **📁 New Subfolder** to add a subfolder inside it.
4. Click **💾 Save Here**.

![Sending a Request](Screenshots/sending-request.png)

### Managing Collections

| Action | How |
|---|---|
| New collection | Toolbar → **📂 New Collection** |
| New subfolder | Right-click a collection in the tree → **📁 New Subfolder** |
| Delete | Right-click → **🗑 Delete** (cascades to all subfolders & requests) |
| Refresh | Toolbar → **🔄 Refresh** |

### Sending Requests to Burp Tools

1. Select a folder in the left panel to load its requests.
2. Click a request in the table to select it.
3. Use the buttons below the table (or right-click):
   - **Send to Repeater** — opens in Repeater with correct host/port/HTTPS
   - **Send to Intruder** — loads for fuzzing
   - **Send to Comparer** — side-by-side diff
   - **Send to Scanner** — active scan *(Burp Pro only)*

### Export & Import

**Export selected collection:**
- Select a collection in the left panel → Toolbar → **📤 Export**

**Export all collections:**
- Toolbar → **🗂 Export All Collections**

**Import:**
- Toolbar → **📥 Import** → choose a previously exported `.json` file
- The full folder hierarchy and all requests are restored

---

## 🗂 Project Structure

```
Request-Jar/
├── pom.xml
└── src/main/java/com/requestjar/
    ├── RequestJarExtension.java        # Entry point, context menu registration
    ├── database/
    │   ├── DatabaseManager.java        # SQLite CRUD + schema migration
    │   ├── Request.java                # Request model (incl. host/port/protocol)
    │   └── Folder.java                 # Folder model
    ├── gui/
    │   ├── RequestJarTab.java          # Main UI tab, collection tree
    │   ├── RequestViewerPanel.java     # Request table + syntax-highlighted viewer
    │   ├── FolderSelectionDialog.java  # Save dialog with inline folder creation
    │   ├── ExportDialog.java           # JSON / CSV export
    │   └── ImportDialog.java           # JSON import
    └── utils/
        └── BurpIntegration.java        # Repeater / Intruder / Comparer / Scanner API
```

---

## 🛠 Troubleshooting

| Problem | Solution |
|---|---|
| Database error on startup | Delete `requestjar.db` from Burp's working directory and restart |
| Scanner button does nothing | Active Scan is a **Burp Pro** feature; not available in Community Edition |



---

## 🤝 Contributing

Feel free to contribute to this open-source project.

---

## 🔨 Building from Source

Only needed if you want to contribute or modify the extension.

### Prerequisites
- Java 15+
- Maven 3.6+
- Burp Suite JAR (for the API — provided as `scope: provided`)

### Steps

```bash
# 1. Clone
git clone https://github.com/<your-username>/Request-Jar.git
cd Request-Jar

# 2. Install the Burp extender API to local Maven repo
#    (replace the path with your actual Burp Suite JAR location)
mvn install:install-file \
  -Dfile="C:/Program Files/BurpSuitePro/burpsuite_pro.jar" \
  -DgroupId=net.burp-extender-api \
  -DartifactId=burp-extender-api \
  -Dversion=2026 \
  -Dpackaging=jar

# 3. Build
mvn clean package

# Output: target/RequestJar-1.0.0.jar
```

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0**. See the [LICENSE](LICENSE) file for the full text.
