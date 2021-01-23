package net.kdt.pojavlaunch.tasks;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.prefs.*;
import net.kdt.pojavlaunch.utils.*;
import net.kdt.pojavlaunch.value.*;
import org.apache.commons.io.*;

public class MinecraftDownloaderTask extends AsyncTask<String, String, Throwable>
 {
    private BaseLauncherActivity mActivity;
    private boolean launchWithError = false;
    MinecraftDownloaderTask thiz = this;
    public MinecraftDownloaderTask(BaseLauncherActivity activity) {
        mActivity = activity;
    }
    
    @Override
    protected void onPreExecute() {
        mActivity.mLaunchProgress.setMax(1);
        mActivity.statusIsLaunching(true);
    }

    private JMinecraftVersionList.Version verInfo;
    @Override
    protected Throwable doInBackground(final String[] p1) {
        Throwable throwable = null;
        try {
            final String downVName = "/" + p1[0] + "/" + p1[0];
            //Downloading libraries
            String minecraftMainJar = Tools.DIR_HOME_VERSION + downVName + ".jar";
            JAssets assets = null;
            try {
                String verJsonDir = Tools.DIR_HOME_VERSION + downVName + ".json";

                verInfo = findVersion(p1[0]);

                if (verInfo.url != null && !new File(verJsonDir).exists()) {
                    publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, p1[0] + ".json"));

                    Tools.downloadFileMonitored(
                        verInfo.url,
                        verJsonDir,
                            new Tools.DownloaderFeedback(){
                                @Override
                                public void updateProgress(int curr, int max) {
                                    publishDownloadProgress(p1[0] + ".json", curr, max);
                                }
                            }
                    );
                }

                verInfo = Tools.getVersionInfo(p1[0]);
                assets = downloadIndex(verInfo.assets, new File(Tools.ASSETS_PATH, "indexes/" + verInfo.assets + ".json"));

                File outLib;
                String libPathURL;

                setMax(verInfo.libraries.length);
                zeroProgress();
                for (final DependentLibrary libItem : verInfo.libraries) {

                    if (
                        libItem.name.startsWith("net.java.jinput") ||
                        libItem.name.startsWith("org.lwjgl")
                    ) { // Black list
                        publishProgress("1", "Ignored " + libItem.name);
                        //Thread.sleep(100);
                    } else {
                        String[] libInfo = libItem.name.split(":");
                        String libArtifact = Tools.artifactToPath(libInfo[0], libInfo[1], libInfo[2]);
                        outLib = new File(Tools.DIR_HOME_LIBRARY + "/" + libArtifact);
                        outLib.getParentFile().mkdirs();

                        if (!outLib.exists()) {
                            publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, libItem.name));

                            boolean skipIfFailed = false;

                            if (libItem.downloads == null || libItem.downloads.artifact == null) {
                                MinecraftLibraryArtifact artifact = new MinecraftLibraryArtifact();
                                artifact.url = (libItem.url == null ? "https://libraries.minecraft.net/" : libItem.url) + libArtifact;
                                libItem.downloads = new DependentLibrary.LibraryDownloads(artifact);

                                skipIfFailed = true;
                            }
                            for (int i = 0, size = 5; i<size; i++){
                                try {
                                    libPathURL = libItem.downloads.artifact.url;
                                    Tools.downloadFileMonitored(
                                            libPathURL,
                                            outLib.getAbsolutePath(),
                                            new Tools.DownloaderFeedback() {
                                                @Override
                                                public void updateProgress(int curr, int max) {
                                                    publishDownloadProgress(libItem.name, curr, max);
                                                }
                                            }
                                    );
                                    break;
                                } catch (Throwable th) {
                                    if (!skipIfFailed) {
                                        throw th;
                                    } else {
                                        th.printStackTrace();
                                        publishProgress("0", th.getMessage());
                                        if (i + 1 == size) throw th;
                                    }
                                }
                            }
                        }
                    }
                }
                setMax(3);
                zeroProgress();
                publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, p1[0] + ".jar"));
                File minecraftMainFile = new File(minecraftMainJar);
                if (!minecraftMainFile.exists() || minecraftMainFile.length() == 0l) {
                    try {
                        Tools.downloadFileMonitored(
                            verInfo.downloads.values().toArray(new MinecraftClientInfo[0])[0].url,
                            minecraftMainJar,
                                new Tools.DownloaderFeedback() {
                                    @Override
                                    public void updateProgress(int curr, int max) {
                                        publishDownloadProgress(p1[0] + ".jar", curr, max);
                                    }
                                }
                        );
                    } catch (Throwable th) {
                        if (verInfo.inheritsFrom != null) {
                            minecraftMainFile.delete();
                            FileInputStream is = new FileInputStream(new File(Tools.DIR_HOME_VERSION, verInfo.inheritsFrom + "/" + verInfo.inheritsFrom + ".jar"));
                            FileOutputStream os = new FileOutputStream(minecraftMainFile);
                            IOUtils.copy(is, os);
                            is.close();
                            os.close();
                        } else {
                            throw th;
                        }
                    }
                }
            } catch (Throwable e) {
                launchWithError = true;
                throw e;
            }

            downloadModPack(p1[0], new File(Tools.DIR_GAME_NEW));

            publishProgress("1", mActivity.getString(R.string.mcl_launch_cleancache));
            // new File(inputPath).delete();

            for (File f : new File(Tools.DIR_HOME_VERSION).listFiles()) {
                if(f.getName().endsWith(".part")) {
                    Log.d(Tools.APP_NAME, "Cleaning cache: " + f);
                    f.delete();
                }
            }

            mActivity.mIsAssetsProcessing = true;
            mActivity.mPlayButton.post(new Runnable(){

                    @Override
                    public void run()
                    {
                        mActivity.mPlayButton.setText("Skip");
                        mActivity.mPlayButton.setEnabled(true);
                    }
                });
            publishProgress("1", mActivity.getString(R.string.mcl_launch_download_assets));
            setMax(assets.objects.size());
            zeroProgress();
            try {
                downloadAssets(assets, verInfo.assets, new File(Tools.ASSETS_PATH));
            } catch (Exception e) {
                e.printStackTrace();

                // Ignore it
                launchWithError = false;
            } finally {
                mActivity.mIsAssetsProcessing = false;
            }
        } catch (Throwable th){
            throwable = th;
        } finally {
            return throwable;
        }
    }
    private int addProgress = 0;

    public void zeroProgress() {
        addProgress = 0;
    }

    public void setMax(final int value)
    {
        mActivity.mLaunchProgress.post(new Runnable(){

                @Override
                public void run()
                {
                    mActivity.mLaunchProgress.setMax(value);
                }
            });
    }
    
    private void publishDownloadProgress(String target, int curr, int max) {
        // array length > 2 ignores append log on dev console
        publishProgress("0", mActivity.getString(R.string.mcl_launch_downloading_progress, target,
            curr / 1024d / 1024d, max / 1024d / 1024d), "");
    }

    @Override
    protected void onProgressUpdate(String... p1)
    {
        int addedProg = Integer.parseInt(p1[0]);
        if (addedProg != -1) {
            addProgress = addProgress + addedProg;
            mActivity.mLaunchProgress.setProgress(addProgress);

            mActivity.mLaunchTextStatus.setText(p1[1]);
        }

        if (p1.length < 3) {
            mActivity.mConsoleView.putLog(p1[1] + "\n");
        }
    }

    @Override
    protected void onPostExecute(Throwable p1)
    {
        mActivity.mPlayButton.setText("Play");
        mActivity.mPlayButton.setEnabled(true);
        mActivity.mLaunchProgress.setMax(100);
        mActivity.mLaunchProgress.setProgress(0);
        mActivity.statusIsLaunching(false);
        if(p1 != null) {
            p1.printStackTrace();
            Tools.showError(mActivity, p1);
        }
        if(!launchWithError) {
            mActivity.mCrashView.setLastCrash("");

            try {
                /*
                 List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
                 jvmArgs.add("-Xms128M");
                 jvmArgs.add("-Xmx1G");
                 */
                Intent mainIntent = new Intent(mActivity, MainActivity.class /* MainActivity.class */);
                // mainIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                if (LauncherPreferences.PREF_FREEFORM) {
                    DisplayMetrics dm = new DisplayMetrics();
                    mActivity.getWindowManager().getDefaultDisplay().getMetrics(dm);

                    ActivityOptions options = (ActivityOptions) ActivityOptions.class.getMethod("makeBasic").invoke(null);
                    Rect freeformRect = new Rect(0, 0, dm.widthPixels / 2, dm.heightPixels / 2);
                    options.getClass().getDeclaredMethod("setLaunchBounds", Rect.class).invoke(options, freeformRect);
                    mActivity.startActivity(mainIntent, options.toBundle());
                } else {
                    mActivity.startActivity(mainIntent);
                }
            }
            catch (Throwable e) {
                Tools.showError(mActivity, e);
            }

            /*
             FloatingIntent maini = new FloatingIntent(PojavLauncherActivity.this, MainActivity.class);
             maini.startFloatingActivity();
             */
        }

        mActivity.mTask = null;
    }

    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";

    public JAssets downloadIndex(String versionName, File output) throws Throwable {
        if (!output.exists()) {
            output.getParentFile().mkdirs();
            DownloadUtils.downloadFile(verInfo.assetIndex != null ? verInfo.assetIndex.url : "http://s3.amazonaws.com/Minecraft.Download/indexes/" + versionName + ".json", output);
        }

        return Tools.GLOBAL_GSON.fromJson(Tools.read(output.getAbsolutePath()), JAssets.class);
    }

    public void downloadAsset(JAssetInfo asset, File objectsDir) throws IOException, Throwable {
        String assetPath = asset.hash.substring(0, 2) + "/" + asset.hash;
        File outFile = new File(objectsDir, assetPath);
        if (!outFile.exists()) {
            DownloadUtils.downloadFile(MINECRAFT_RES + assetPath, outFile);
        }
    }

    public void downloadAssets(JAssets assets, String assetsVersion, File outputDir) throws IOException, Throwable {
        File hasDownloadedFile = new File(outputDir, "downloaded/" + assetsVersion + ".downloaded");
        if (!hasDownloadedFile.exists()) {
            System.out.println("Assets begin time: " + System.currentTimeMillis());
            Map<String, JAssetInfo> assetsObjects = assets.objects;
            mActivity.mLaunchProgress.setMax(assetsObjects.size());
            zeroProgress();
            File objectsDir = new File(outputDir, "objects");
            int downloadedSs = 0;
            for (JAssetInfo asset : assetsObjects.values()) {
                if (!mActivity.mIsAssetsProcessing) {
                    return;
                }

                downloadAsset(asset, objectsDir);
                publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, assetsObjects.keySet().toArray(new String[0])[downloadedSs]));
                downloadedSs++;
            }
            hasDownloadedFile.getParentFile().mkdirs();
            hasDownloadedFile.createNewFile();
            System.out.println("Assets end time: " + System.currentTimeMillis());
        }
    }

     private static final String FORGE_INSTALLER_PATH = "net/minecraftforge/forge/%s-%s/forge-%s-%s-installer.jar";
     private static final String FORGE_INSTALLER_URL = "https://storage.ixnah.com/maven/" + FORGE_INSTALLER_PATH;
    public void downloadModPack(String version, File outputDir) throws Throwable {
        zeroProgress();
        File currentManifestFile = new File(outputDir, "server-manifest.json");
        String json = DownloadUtils.downloadString("https://storage.ixnah.com/Minecraft/server-manifest.json");
        publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, currentManifestFile.getName()));
        JsonObject remoteManifest = JsonParser.parseString(json).getAsJsonObject();
        String fileApi = remoteManifest.get("fileApi").getAsString();
        fileApi = fileApi.endsWith("/") ? fileApi : fileApi + "/";
        JsonArray addons = remoteManifest.has("addons") ? remoteManifest.getAsJsonArray("addons") : new JsonArray();
        String forgeVersion = "";
        for (int i = 0, size = addons.size(); i < size; i++) {
            JsonObject addon = addons.get(i).getAsJsonObject();
            String id = addon.get("id").getAsString();
            String addonVersion = addon.get("version").getAsString();
            if (id.equals("forge")) {
                forgeVersion = addonVersion;
            } else if (id.equals("game") && !addonVersion.equals(version)) {
                return; // 如果当前MC版本不为清单文件的版本，退出下载
            }
        }
        JsonObject currentManifest = new JsonObject();
        if (currentManifestFile.exists()) {
            currentManifest = JsonParser.parseReader(new FileReader(currentManifestFile)).getAsJsonObject();
        }
        String remoteVersion = remoteManifest.has("version") ? remoteManifest.get("version").getAsString() : "";
        String currentVersion = currentManifest.has("version") ? currentManifest.get("version").getAsString() : "";
        if (remoteVersion.equals(currentVersion)) return; // 当前MOD包与远程服务器一致，退出下载

        File modpackFile = new File(outputDir, "modpack/modpack.zip");
        File modpackDirFile = new File(outputDir, "modpack");
        zeroProgress();
        publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, modpackFile.getName()));
        for (int i = 0, size = 5; i < size; i ++) {
            try {
                Tools.downloadFileMonitored(
                        fileApi + modpackFile.getName(),
                        modpackFile.getAbsolutePath(),
                        new Tools.DownloaderFeedback() {
                            @Override
                            public void updateProgress(int curr, int max) {
                                publishDownloadProgress(modpackFile.getName(), curr, max);
                            }
                        }
                );
                Tools.ZipTool.unzip(modpackFile, modpackDirFile);
                File overridesDirFile = new File(modpackDirFile, "overrides");
                for(File file : overridesDirFile.listFiles()) {
                    Tools.moveRecursive(file, outputDir);
                }
                break;
            } catch (Throwable th) {
                th.printStackTrace();
                publishProgress("0", th.getMessage());
                if (i + 1 == size) throw th;
            }
        }

//        File mcbbsMirrorReplacerFile = new File(outputDir, "modpack/McbbsMirror-1.0.jar");
//        DownloadUtils.downloadFile("https://storage.ixnah.com/Minecraft/McbbsMirror-1.0.jar", mcbbsMirrorReplacerFile);
//        publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, mcbbsMirrorReplacerFile.getName()));
        String forgeInstallerUrl = String.format(FORGE_INSTALLER_URL, version, forgeVersion, version, forgeVersion);
        File forgeInstallerFile = new File(outputDir, "libraries/" + String.format(FORGE_INSTALLER_PATH, version, forgeVersion, version, forgeVersion));
        zeroProgress();
        publishProgress("1", mActivity.getString(R.string.mcl_launch_downloading, forgeInstallerFile.getName()));
        for (int i = 0, size = 5; i < size; i ++) {
            try {
                Tools.downloadFileMonitored(
                        forgeInstallerUrl,
                        forgeInstallerFile.getAbsolutePath(),
                        new Tools.DownloaderFeedback() {
                            @Override
                            public void updateProgress(int curr, int max) {
                                publishDownloadProgress(forgeInstallerFile.getName(), curr, max);
                            }
                        }
                );
                break;
            } catch (Throwable th) {
                th.printStackTrace();
                publishProgress("0", th.getMessage());
                if (i + 1 == size) throw th;
            }
        }

        // 提前下载Forge所需的支持库
        File librariesDirFile = new File(outputDir, "libraries");
        JsonObject installProfile;
        JsonObject versionJson = null;
        try (ZipFile forgeInstallerZip = new ZipFile(forgeInstallerFile)) {
            ZipEntry installProfileEntry = forgeInstallerZip.stream().filter(entry -> entry.getName().equals("install_profile.json"))
                    .findFirst().orElseThrow(() -> new RuntimeException("install_profile.json"));
            InputStream inputStream = forgeInstallerZip.getInputStream(installProfileEntry);
            installProfile = JsonParser.parseReader(new InputStreamReader(inputStream)).getAsJsonObject();
            ZipEntry versionJsonEntry = forgeInstallerZip.stream().filter(entry -> entry.getName().equals("version.json"))
                    .findFirst().orElse(null);
            if (versionJsonEntry != null) {
                inputStream = forgeInstallerZip.getInputStream(installProfileEntry);
                versionJson = JsonParser.parseReader(new InputStreamReader(inputStream)).getAsJsonObject();
            }
        }
        JsonArray libraries = installProfile != null && installProfile.has("libraries") ?
                installProfile.getAsJsonArray("libraries") : new JsonArray();
        JsonArray versionLibraries = versionJson != null && versionJson.has("libraries") ?
                versionJson.getAsJsonArray("libraries") : new JsonArray();
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            String name = library.get("name").getAsString();
            JsonObject downloads = library.get("downloads").getAsJsonObject();
            JsonObject artifact = downloads.get("artifact").getAsJsonObject();
            String path = artifact.get("path").getAsString();
            String url = artifact.get("url").getAsString();
            if (url.isEmpty()) {
                System.out.println("url is empty! " + library);
                continue;
            }
            File libraryFile = new File(librariesDirFile, path);
            for (int i = 0, size = 5; i < size; i ++) {
                try {
                    Tools.downloadFileMonitored(
                            url,
                            libraryFile.getAbsolutePath(),
                            new Tools.DownloaderFeedback() {
                                @Override
                                public void updateProgress(int curr, int max) {
                                    publishDownloadProgress(name, curr, max);
                                }
                            }
                    );
                    break;
                } catch (Throwable th) {
                    th.printStackTrace();
                    publishProgress("0", th.getMessage());
                    publishProgress("0", library.toString());
                    if (i + 1 == size) throw th;
                }
            }
        }
        for (JsonElement element : versionLibraries) {
            JsonObject library = element.getAsJsonObject();
            String name = library.get("name").getAsString();
            JsonObject downloads = library.get("downloads").getAsJsonObject();
            JsonObject artifact = downloads.get("artifact").getAsJsonObject();
            String path = artifact.get("path").getAsString();
            String url = artifact.get("url").getAsString();
            if (url.isEmpty()) continue;
            File libraryFile = new File(librariesDirFile, path);
            for (int i = 0, size = 5; i < size; i ++) {
                try {
                    Tools.downloadFileMonitored(
                            url,
                            libraryFile.getAbsolutePath(),
                            new Tools.DownloaderFeedback() {
                                @Override
                                public void updateProgress(int curr, int max) {
                                    publishDownloadProgress(name, curr, max);
                                }
                            }
                    );
                    break;
                } catch (Throwable th) {
                    th.printStackTrace();
                    publishProgress("0", th.getMessage());
                    publishProgress("0", library.toString());
                    if (i + 1 == size) throw th;
                }
            }
        }

        // 安装Forge
        Intent intent = new Intent(mActivity, JavaGUILauncherActivity.class);
        intent.putExtra("modFile", forgeInstallerFile);
        mActivity.startActivity(intent);

        try (FileWriter writer = new FileWriter(currentManifestFile)) {
            writer.write(json);
        }
    }

    private JMinecraftVersionList.Version findVersion(String version) {
        if (mActivity.mVersionList != null) {
            for (JMinecraftVersionList.Version valueVer: mActivity.mVersionList.versions) {
                if (valueVer.id.equals(version)) {
                    return valueVer;
                }
            }
        }

        // Custom version, inherits from base.
        return Tools.getVersionInfo(version);
    }
}
