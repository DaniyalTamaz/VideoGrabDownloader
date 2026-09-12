# SportNexa Admin

Lightweight browser dashboard for editing and publishing the remote SportNexa channel database.

## What it manages

- Channel name
- Category
- Primary stream URL
- Backup stream URL
- Logo URL
- Enabled / disabled state
- Featured state
- Display order
- JSON import/export
- Direct publish to `sportnexa/channels.json`

## Open the panel

Open `index.html` in a modern browser. The dashboard automatically reads the current live database from:

`https://raw.githubusercontent.com/DaniyalTamaz/VideoGrabDownloader/main/sportnexa/channels.json`

No Node.js, Visual Studio, or web server is required for the basic dashboard.

## Publishing securely

The Publish button requires a GitHub fine-grained personal access token at publish time.

Recommended token scope:

- Repository access: **Only select repositories** → `VideoGrabDownloader`
- Repository permission: **Contents → Read and write**
- Do not grant unrelated permissions.

The dashboard does not store the token in localStorage, sessionStorage, cookies, the JSON database, or the Git repository. The token field is cleared after a successful publish.

## Workflow

1. Reload Live.
2. Add, edit, disable, feature, delete, or reorder channels.
3. Review the totals and channel list.
4. Paste the fine-grained token.
5. Click Publish.
6. SportNexa v0.4+ receives the new database when Live TV is refreshed/opened.

Only add stream URLs and logos that you are authorized to distribute or use in the app.
