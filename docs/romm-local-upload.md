# Uploading local ROMs to RomM

Argosy can copy ROMs from the handheld into a RomM library over **SFTP** or **SMB2/3**. File
transfers never use RomM's HTTP upload API; the RomM API is used only to read the library layout,
trigger a scan and refresh Argosy afterwards.

## Adding a server

Open **Settings → RomM → ROM uploads** and choose **Add upload server**. Fill in the protocol,
host, port, username and password or private key, plus the share for SMB and the library root.
**Test connection** saves the server, connects, checks that the library root is a folder and counts
the platform folders it finds. On the first SFTP connection Argosy asks you to confirm the server's
host-key fingerprint. Leaving the editor with Back saves a complete server; an incomplete new
server is discarded.

Passwords, private keys and passphrases are stored encrypted on the device and are never shown
again; the editor only says whether one is saved. The same screen holds the transfer settings
(duplicates, simultaneous uploads, Wi-Fi only, charging only, speed limits, notifications) and the
RomM scan settings.

## Uploading one game

Open a downloaded game, press the More Options button, and choose **Upload to RomM**. The entry
appears once at least one server is saved, for every downloaded game except Steam games and
Android apps.

The dialog starts on your default server and your duplicate setting. Left and right change
either one; changing the server re-checks where the game would land. Below them it shows the
platform, the remote folder the files will be written to, and the file count and size. Disc sets
bring their sibling files along, and any file a playlist or cue sheet names but the folder lacks
is listed as missing.

**Upload** is disabled when RomM has no known folder for the platform. Choosing **Overwrite**
asks for a second confirmation before anything is queued. Once queued, the upload runs in the
background like any other.

## Upload queue

Settings → RomM → ROM uploads → **Upload queue** lists every upload. Running and waiting uploads
are on top with their progress; finished, skipped, failed and cancelled ones sit below, newest
first. When uploads are held for Wi-Fi, a charger or a network, the reason shows above the list.

Select an upload to pause, resume, retry or cancel it. Completed and skipped uploads have nothing
to do. **Pause all**, **Resume all** and **Clear finished** sit at the bottom; clearing only tidies
the list and never touches files on the device or the server.

## Importing local ROMs

Settings → RomM → ROM uploads → **Import local ROMs to RomM** opens a file browser where you
can tick any number of files and folders. In the browser, A opens a folder, Y ticks or unticks the
focused row (or tap its box), X finishes, and B goes back.

Argosy then plans the selection against the server you pick. A folder named after a platform
becomes one game per file group and subfolder, a folder of platform folders is treated as a
library, and any other folder is a single game that keeps its internal structure. The dialog
reports how many games are ready, how many have a platform guessed only from the file extension
(left out unless you turn on **Include uncertain platforms**), how many have no RomM folder and
will be skipped, and how many selections could not be read.

### Quick upload folder

**Quick upload folder** remembers one folder on this device. Choose it once with the folder
browser, then **Upload quick folder to RomM** plans everything in it against your default server
and opens the same dialog as above, so you can check the platform guesses and the duplicate
setting before pressing **Upload**. Pressing the upload button with no folder saved opens the
folder browser first. The folder path is a location on this device and is not part of a settings
backup.

### Upload missing ROMs

**Upload missing ROMs**, next to it, takes every game Argosy found on this device that RomM does
not have (local-only games with files) and runs the same dialog on them. Games already synced
from RomM are never touched, and the dialog says so when nothing is missing.

## How it works

1. Argosy groups the selected files into games. Multi-disc and multi-file sets (`.m3u`, `.cue` +
   `.bin`, `.gdi` + tracks, `.ccd` + `.img`/`.sub`, `.mds` + `.mdf`) stay together.
2. Each game gets an Argosy platform from, in order: a folder mapping you set, the platform Argosy
   already knows for the game, the source folder name, or an extension that belongs to exactly one
   platform. Shared extensions (`.bin`, `.iso`, `.chd`, `.zip`, ...) never decide on their own; you
   are asked to confirm.
3. The platform is turned into the RomM folder name (the RomM `fs_slug`): your remote mapping first,
   then the folder RomM already uses for that platform in your synced library, then RomM's default
   name. Argosy and RomM slugs differ for some systems, for example GameCube is `gc` in Argosy and
   `ngc` in RomM, Dreamcast is `dreamcast` and `dc`.
4. Every file is written to a hidden temporary name (`.argosy-<id>-<name>.part`) beside its target.
   The remote size is compared with the local size, and only then is the file renamed into place.
   A failed or cancelled transfer removes the temporary file and never leaves a half-written ROM
   under its real name.
5. When the batch ends Argosy can ask RomM to scan the platform folders it wrote to, then pulls the
   new games into its library.

Local ROMs are never deleted or modified. Argosy never asks for root.

## Library layout

RomM's library is mounted at `/romm/library` inside the container. The default layout is:

```
library/
  roms/
    gba/
      Pokemon Emerald.gba
    psx/
      Final Fantasy VII (Disc 1).chd
      Final Fantasy VII.m3u
```

The alternative layout `library/{platform}/roms/{game}` is also supported: set the platform
template in the upload profile to `{platform}/roms`.

By default Argosy refuses to create a platform folder that does not exist yet, so a wrong mapping
cannot scatter new folders through your library. Game subfolders inside an existing platform folder
are always allowed. Enable "Create missing platform folders" on the profile if you want new systems
created automatically.

## Duplicates

The default is **Skip**: a file that already exists on the server is left alone and counted as
"already there". The other choices are **Keep both** (the new file becomes `Name (2).ext`),
**Overwrite** (only after an explicit confirmation, replaced after the new copy is verified) and
**Cancel** (the batch stops at the first existing file). Argosy never overwrites silently.

## Setting up SFTP

SFTP is the recommended protocol.

- Use any SSH server that can see the RomM library directory on the host: OpenSSH on the host
  itself, or an SFTP container that mounts the same directory.
- The **library root** in the profile is the path of the library as the SSH user sees it, for
  example `/srv/romm/library`, not the container path `/romm/library`.
- Password and private-key login are supported. Keys can be OpenSSH or PKCS#8 PEM, with or without a
  passphrase.
- On the first connection Argosy shows the server's host-key fingerprint (`SHA256:...`). Compare it
  with the server, for example `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`, before trusting
  it. If the key later changes, uploads stop with a security warning until you review and trust the
  new key. Host-key checking cannot be turned off.

Example SFTP sidecar for a RomM compose file, writing as uid/gid 1000 (the user RomM runs as):

```yaml
  romm-sftp:
    image: atmoz/sftp:alpine
    restart: unless-stopped
    ports:
      - "2222:22"
    volumes:
      - /path/to/library:/home/romm/library
    command: romm:change-this-password:1000:1000
```

With this sidecar the profile uses port `2222`, user `romm` and library root `/library` (the
container chroots the user to its home directory). Mount the same host directory that RomM mounts at
`/romm/library`.

## Setting up SMB

- SMB 2.0.2 through 3.1.1 are offered; SMB1 is never negotiated. Message signing is required.
  Enable "Require encryption" for SMB3 encryption when the server supports it.
- The profile needs the **share** name and, optionally, a library root inside the share, for example
  share `media`, library root `romm/library`. Leave the root empty when the share is the library.
- Make sure the share user can create and rename files in the platform folders.

## SFTP server on the handheld

Settings > RomM > ROM uploads > SFTP server turns the handheld into an SFTP server, so a computer on the same
Wi-Fi can copy ROMs to and from the ROM folder chosen under Storage.

- Off by default and never persisted as "on": it runs only after you flip the switch, and stays off after the app
  or the device restarts. A foreground notification shows the address and has a Stop action.
- Port 2222 by default (2222, 2022, 8022 and 22222 are offered). Ports below 1024 need root on Android.
- Login is user `argosy` with a random password shown in Connection details. New password replaces it and signs
  connected computers out. The password is kept in the same encrypted store as the upload credentials.
- Read-only unless Allow uploads is on. Peers outside private, link-local and loopback ranges are dropped, and a
  connection gets three authentication attempts.
- Changing the port, Allow uploads or the password while it runs restarts the server.
- Only SFTP. There is no SMB server (port 445 needs root) and no email.

Android caveats: the server is Apache MINA SSHD, which targets desktop Java. It is compiled into the app and its
logic is unit tested on a JVM, but behaviour on Android (host key generation, file attributes, large transfers)
has to be confirmed on a device. If the handheld sleeps the Wi-Fi radio can drop; keep the screen on while copying.

## Docker bind mounts and named volumes

Argosy writes through the SSH or SMB server, so that server has to see the **same files** RomM reads.

- **Bind mount** (`/path/to/library:/romm/library`): point the SSH or SMB server at
  `/path/to/library` on the host. This is the simplest setup.
- **Named volume** (`romm_library:/romm/library`): mount the same named volume into the SFTP
  container, or find its host path with `docker volume inspect romm_library` and share that.
  Do not copy files into `/var/lib/docker/volumes/...` from outside a container unless you know the
  permissions you are writing with.

Files must be readable by RomM, which runs as uid/gid 1000 in the official image. Writing as the
same uid avoids permission problems; otherwise make the platform folders group-writable.

## RomM scan after upload

Argosy uses `POST /api/tasks/scan` (RomM 5.4.0 and later) with a quick scan limited to the platform
folders that received files, and follows the task on `GET /api/tasks/{task_id}`. Both endpoints
require the `tasks.run` scope.

The token Argosy signs in with does not include `tasks.run`. To let Argosy start scans, create a
client token in RomM with the `tasks.run` scope (an account allowed to run tasks is required) and
enter it as the **scan token** in Settings. It is stored encrypted on the device, separately from
your RomM sign-in.

Possible results after a batch:

| State | Meaning |
| --- | --- |
| Uploaded | Files are on the server under their final names. |
| Scan completed | RomM finished scanning the folders. |
| Indexed | Argosy pulled the library changes; new games appear in Argosy. |
| Not scanned | Server older than 5.4.0, no scan token or missing `tasks.run`, no scan worker running, or a scan was already in progress. Start a scan in RomM's web UI, or rely on RomM's scheduled scans or `ENABLE_RESCAN_ON_FILESYSTEM_CHANGE`. |

## Background transfers

Uploads keep running when Argosy is minimized, another app or an emulator is in front, or the screen
is off. They run under a `dataSync` foreground service started through WorkManager, with a progress
notification that has Pause/Resume/Cancel.

- **Network loss:** the job moves to "Waiting for a network connection" and resumes when the
  network returns. Transient errors are retried with exponential backoff (5 s, 10 s, 20 s, ... up to
  10 minutes, five attempts).
- **Process death or reboot:** the queue is saved on the device and restored. A file that was in
  flight starts again from the beginning; its temporary file is discarded first. Remote files are
  never appended to, because a partial remote file cannot be trusted. Files of a set that already
  reached their final name are not sent again.
- **While a game is running:** only one job runs at a time and bandwidth is capped by the in-game
  limit, so the emulator keeps the CPU and network.
- **Wi-Fi only / charging only:** jobs wait until the condition holds; WorkManager restarts them when
  it does.

### ROM downloads

Downloads from RomM follow the same rules, with these differences.

- **Resume:** a partial download keeps its `.tmp` file and continues with an HTTP `Range` request.
  The check is the file size and the server's status code; Argosy does not compare an ETag, so a
  file replaced on the server with the same length would not be noticed.
- **Retry:** a dropped connection, a timeout or a 408/425/429/5xx reply requeues the download with
  exponential backoff (5 s up to 5 minutes). Attempts that moved no new bytes count towards six;
  one that made progress starts the count over. Other errors fail the download as before.
- **Network return:** when connectivity comes back, waiting downloads start at once instead of
  waiting out their backoff.
- **Wi-Fi only, charging only, speed limit:** Settings → Storage → Downloads. While a condition
  does not hold, running downloads are parked in the queue with their partial files and start again
  when it holds. The speed limit applies to every running download together.
- **Disk full:** running out of space mid-download parks the download as waiting for storage rather
  than failing it. It resumes when you free space and open the downloads drawer.
- **Notification:** Pause, Resume (when something is paused) and Cancel act on every ROM download.
  Cancel discards the partial files.
- **Process death:** the queue is restored when Argosy starts. A 15-minute WorkManager job, active
  only while downloads are queued and a network exists, starts Argosy again so the queue restores
  without you opening the app. Android may delay it under Doze or battery restrictions.

### Android limits

- Android 13 and later need the notification permission to show transfer progress. Transfers still
  run without it, but you cannot see or control them from the notification shade.
- Android 15 limits `dataSync` foreground services to about 6 hours per 24 hours. Very large batches
  can be paused by the system; they resume on the next run.
- Doze and aggressive OEM battery managers can delay background work. If transfers stall with the
  screen off, exempt Argosy from battery optimization in system settings.
- A foreground service cannot be started from the background on recent Android versions. Argosy
  relies on WorkManager for restarts, so a restart after reboot or network return can take a short
  while.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Host key unknown | First connection to this server: compare and trust the fingerprint in the profile. |
| Host key changed | The server was reinstalled, its keys were regenerated, or something is intercepting the connection. Verify on the server before trusting the new key. |
| Authentication failed | Username, password or key; for SMB also the domain. |
| Platform folder missing | The RomM folder for this system does not exist. Check the remote folder mapping or enable creating missing platform folders. |
| Permission denied | The SSH/SMB user cannot write to the platform folder. See the uid/gid notes above. |
| Destination invalid | The library root or template points outside the share or to a file. Use "Test connection" in the profile. |
| Size mismatch | The server stored a different size than the local file. Usually a full disk or a filesystem quota; the upload is retried. |
| Not enough space | Free space on the server. |
| Uploaded but no new game in Argosy | See "RomM scan after upload". A game RomM cannot identify still appears after a scan, unmatched. |
