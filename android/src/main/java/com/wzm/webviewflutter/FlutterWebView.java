// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.wzm.webviewflutter;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.webkit.JsPromptResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Size;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.platform.PlatformView;
import util.FileUtil;
import android.content.pm.PackageManager;

public class FlutterWebView implements PlatformView, MethodCallHandler{
  private static final String TAG = "FlutterWebView";

  private static final String JS_CHANNEL_NAMES_FIELD = "javascriptChannelNames";
  private final WebView webView;
  private final MethodChannel methodChannel;
  private final FlutterWebViewClient flutterWebViewClient;
  private final Handler platformThreadHandler;

  private ValueCallback<Uri> uploadMessage;
  private ValueCallback<Uri[]> uploadMessageAboveL;
  private final static int FILE_CHOOSER_RESULT_CODE = 10000;
  public static final int RESULT_OK = -1;

  private String[] perms = {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
  private static final int REQUEST_CAMERA = 1;
  private static final int REQUEST_LOCATION = 100;
  private Uri cameraUri;

  // Verifies that a url opened by `Window.open` has a secure url.
  private class FlutterWebChromeClient extends WebChromeClient {
    @Override
    public boolean onCreateWindow(
            final WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
      final WebViewClient webViewClient =
              new WebViewClient() {
                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                @Override
                public boolean shouldOverrideUrlLoading(
                        @NonNull WebView view, @NonNull WebResourceRequest request) {
                  final String url = request.getUrl().toString();
                  if (!flutterWebViewClient.shouldOverrideUrlLoading(
                          FlutterWebView.this.webView, request)) {
                    webView.loadUrl(url);
                  }
                  return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                  if (!flutterWebViewClient.shouldOverrideUrlLoading(
                          FlutterWebView.this.webView, url)) {
                    webView.loadUrl(url);
                  }
                  return true;
                }

              };

      final WebView newWebView = new WebView(view.getContext());
      newWebView.setWebViewClient(webViewClient);

      final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
      transport.setWebView(newWebView);
      resultMsg.sendToTarget();

      return true;
    }

    @Override
    public void onProgressChanged(WebView view, int progress) {
      flutterWebViewClient.onLoadingProgress(progress);
    }

    // For Android < 3.0
    public void openFileChooser(ValueCallback<Uri> valueCallback) {
      Log.v(TAG, "openFileChooser Android < 3.0");
      if(uploadMessage!=null){
        uploadMessage.onReceiveValue(null);
      }
      uploadMessage = valueCallback;
      takePhotoOrOpenGallery();
    }

    // For Android  >= 3.0
    public void openFileChooser(ValueCallback valueCallback, String acceptType) {
      Log.v(TAG, "openFileChooser Android  >= 3.0");
      uploadMessage = valueCallback;
      takePhotoOrOpenGallery();
    }

    //For Android  >= 4.1
    public void openFileChooser(ValueCallback<Uri> valueCallback, String acceptType, String capture) {
      Log.v(TAG, "openFileChooser Android  >= 4.1");
      uploadMessage = valueCallback;
      takePhotoOrOpenGallery();
    }

    // For Android >= 5.0
    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
      Log.v(TAG, "openFileChooser Android >= 5.0");
      uploadMessageAboveL = filePathCallback;
      takePhotoOrOpenGallery();
      return true;
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
      callback.invoke(origin, true, false);
      super.onGeolocationPermissionsShowPrompt(origin, callback);
    }
  }

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  @SuppressWarnings("unchecked")
  FlutterWebView(
          final Context context,
          BinaryMessenger messenger,
          int id,
          Map<String, Object> params,
          View containerView) {

    DisplayListenerProxy displayListenerProxy = new DisplayListenerProxy();
    DisplayManager displayManager =
            (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    displayListenerProxy.onPreWebViewInitialization(displayManager);

    Boolean usesHybridComposition = (Boolean) params.get("usesHybridComposition");
    webView =
            (usesHybridComposition)
                    ? new WebView(context)
                    : new InputAwareWebView(context, containerView);

    displayListenerProxy.onPostWebViewInitialization(displayManager);

    platformThreadHandler = new Handler(context.getMainLooper());
    // Allow local storage.
    webView.getSettings().setDomStorageEnabled(true);
    webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);

    // Multi windows is set with FlutterWebChromeClient by default to handle internal bug: b/159892679.
    webView.getSettings().setSupportMultipleWindows(true);
    webView.setWebChromeClient(new FlutterWebChromeClient());

    methodChannel = new MethodChannel(messenger, "plugins.flutter.io/webview_" + id);
    methodChannel.setMethodCallHandler(this);

    flutterWebViewClient = new FlutterWebViewClient(methodChannel);
    Map<String, Object> settings = (Map<String, Object>) params.get("settings");
    if (settings != null) applySettings(settings);

    if (params.containsKey(JS_CHANNEL_NAMES_FIELD)) {
      List<String> names = (List<String>) params.get(JS_CHANNEL_NAMES_FIELD);
      if (names != null) registerJavaScriptChannelNames(names);
    }

    Integer autoMediaPlaybackPolicy = (Integer) params.get("autoMediaPlaybackPolicy");
    if (autoMediaPlaybackPolicy != null) updateAutoMediaPlaybackPolicy(autoMediaPlaybackPolicy);
    if (params.containsKey("userAgent")) {
      String userAgent = (String) params.get("userAgent");
      updateUserAgent(userAgent);
    }
    if (params.containsKey("initialUrl")) {
      String url = (String) params.get("initialUrl");
      webView.loadUrl(url);
    }
  }

  @Override
  public View getView() {
    return webView;
  }

  // @Override
  // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
  // annotation would cause compile time failures in versions of Flutter too old to include the new
  // method. However leaving it raw like this means that the method will be ignored in old versions
  // of Flutter but used as an override anyway wherever it's actually defined.
  // TODO(mklim): Add the @Override annotation once flutter/engine#9727 rolls to stable.
  public void onInputConnectionUnlocked() {
    if (webView instanceof InputAwareWebView) {
      ((InputAwareWebView) webView).unlockInputConnection();
    }
  }

  // @Override
  // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
  // annotation would cause compile time failures in versions of Flutter too old to include the new
  // method. However leaving it raw like this means that the method will be ignored in old versions
  // of Flutter but used as an override anyway wherever it's actually defined.
  // TODO(mklim): Add the @Override annotation once flutter/engine#9727 rolls to stable.
  public void onInputConnectionLocked() {
    if (webView instanceof InputAwareWebView) {
      ((InputAwareWebView) webView).lockInputConnection();
    }
  }

  // @Override
  // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
  // annotation would cause compile time failures in versions of Flutter too old to include the new
  // method. However leaving it raw like this means that the method will be ignored in old versions
  // of Flutter but used as an override anyway wherever it's actually defined.
  // TODO(mklim): Add the @Override annotation once stable passes v1.10.9.
  public void onFlutterViewAttached(View flutterView) {
    if (webView instanceof InputAwareWebView) {
      ((InputAwareWebView) webView).setContainerView(flutterView);
    }
  }

  // @Override
  // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
  // annotation would cause compile time failures in versions of Flutter too old to include the new
  // method. However leaving it raw like this means that the method will be ignored in old versions
  // of Flutter but used as an override anyway wherever it's actually defined.
  // TODO(mklim): Add the @Override annotation once stable passes v1.10.9.
  public void onFlutterViewDetached() {
    if (webView instanceof InputAwareWebView) {
      ((InputAwareWebView) webView).setContainerView(null);
    }
  }

  @Override
  public void onMethodCall(MethodCall methodCall, Result result) {
    switch (methodCall.method) {
      case "loadUrl":
        loadUrl(methodCall, result);
        break;
      case "updateSettings":
        updateSettings(methodCall, result);
        break;
      case "canGoBack":
        canGoBack(result);
        break;
      case "canGoForward":
        canGoForward(result);
        break;
      case "goBack":
        goBack(result);
        break;
      case "goForward":
        goForward(result);
        break;
      case "reload":
        reload(result);
        break;
      case "currentUrl":
        currentUrl(result);
        break;
      case "evaluateJavascript":
        evaluateJavaScript(methodCall, result);
        break;
      case "addJavascriptChannels":
        addJavaScriptChannels(methodCall, result);
        break;
      case "removeJavascriptChannels":
        removeJavaScriptChannels(methodCall, result);
        break;
      case "clearCache":
        clearCache(result);
        break;
      case "getTitle":
        getTitle(result);
        break;
      case "scrollTo":
        scrollTo(methodCall, result);
        break;
      case "scrollBy":
        scrollBy(methodCall, result);
        break;
      case "getScrollX":
        getScrollX(result);
        break;
      case "getScrollY":
        getScrollY(result);
        break;
      default:
        result.notImplemented();
    }
  }

  @SuppressWarnings("unchecked")
  private void loadUrl(MethodCall methodCall, Result result) {
    Map<String, Object> request = (Map<String, Object>) methodCall.arguments;
    String url = (String) request.get("url");
    Map<String, String> headers = (Map<String, String>) request.get("headers");
    if (headers == null) {
      headers = Collections.emptyMap();
    }
    webView.loadUrl(url, headers);
    result.success(null);
  }

  private void canGoBack(Result result) {
    result.success(webView.canGoBack());
  }

  private void canGoForward(Result result) {
    result.success(webView.canGoForward());
  }

  private void goBack(Result result) {
    if (webView.canGoBack()) {
      webView.goBack();
    }
    result.success(null);
  }

  private void goForward(Result result) {
    if (webView.canGoForward()) {
      webView.goForward();
    }
    result.success(null);
  }

  private void reload(Result result) {
    webView.reload();
    result.success(null);
  }

  private void currentUrl(Result result) {
    result.success(webView.getUrl());
  }

  @SuppressWarnings("unchecked")
  private void updateSettings(MethodCall methodCall, Result result) {
    applySettings((Map<String, Object>) methodCall.arguments);
    result.success(null);
  }

  @TargetApi(Build.VERSION_CODES.KITKAT)
  private void evaluateJavaScript(MethodCall methodCall, final Result result) {
    String jsString = (String) methodCall.arguments;
    if (jsString == null) {
      throw new UnsupportedOperationException("JavaScript string cannot be null");
    }
    webView.evaluateJavascript(
            jsString,
            new android.webkit.ValueCallback<String>() {
              @Override
              public void onReceiveValue(String value) {
                result.success(value);
              }
            });
  }

  @SuppressWarnings("unchecked")
  private void addJavaScriptChannels(MethodCall methodCall, Result result) {
    List<String> channelNames = (List<String>) methodCall.arguments;
    registerJavaScriptChannelNames(channelNames);
    result.success(null);
  }

  @SuppressWarnings("unchecked")
  private void removeJavaScriptChannels(MethodCall methodCall, Result result) {
    List<String> channelNames = (List<String>) methodCall.arguments;
    for (String channelName : channelNames) {
      webView.removeJavascriptInterface(channelName);
    }
    result.success(null);
  }

  private void clearCache(Result result) {
    webView.clearCache(true);
    WebStorage.getInstance().deleteAllData();
    result.success(null);
  }

  private void getTitle(Result result) {
    result.success(webView.getTitle());
  }

  private void scrollTo(MethodCall methodCall, Result result) {
    Map<String, Object> request = methodCall.arguments();
    int x = (int) request.get("x");
    int y = (int) request.get("y");

    webView.scrollTo(x, y);

    result.success(null);
  }

  private void scrollBy(MethodCall methodCall, Result result) {
    Map<String, Object> request = methodCall.arguments();
    int x = (int) request.get("x");
    int y = (int) request.get("y");

    webView.scrollBy(x, y);
    result.success(null);
  }

  private void getScrollX(Result result) {
    result.success(webView.getScrollX());
  }

  private void getScrollY(Result result) {
    result.success(webView.getScrollY());
  }

  private void applySettings(Map<String, Object> settings) {
    for (String key : settings.keySet()) {
      switch (key) {
        case "jsMode":
          Integer mode = (Integer) settings.get(key);
          if (mode != null) updateJsMode(mode);
          break;
        case "hasNavigationDelegate":
          final boolean hasNavigationDelegate = (boolean) settings.get(key);

          final WebViewClient webViewClient =
                  flutterWebViewClient.createWebViewClient(hasNavigationDelegate);

          webView.setWebViewClient(webViewClient);
          break;
        case "debuggingEnabled":
          final boolean debuggingEnabled = (boolean) settings.get(key);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setWebContentsDebuggingEnabled(debuggingEnabled);
          }
          break;
        case "hasProgressTracking":
          flutterWebViewClient.hasProgressTracking = (boolean) settings.get(key);
          break;
        case "gestureNavigationEnabled":
          break;
        case "geolocationEnabled":
          final boolean geolocationEnabled = (boolean) settings.get(key);
          webView.getSettings().setGeolocationEnabled(geolocationEnabled);
          if (geolocationEnabled && Build.VERSION.SDK_INT >= 23) {
            int checkPermission = ContextCompat.checkSelfPermission(WebViewFlutterPlugin.activity, Manifest.permission.ACCESS_COARSE_LOCATION);
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
              ActivityCompat.requestPermissions(WebViewFlutterPlugin.activity,
                      new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},
                      REQUEST_LOCATION);
            }
          }
          break;
        case "userAgent":
          updateUserAgent((String) settings.get(key));
          break;
        case "allowsInlineMediaPlayback":
          // no-op inline media playback is always allowed on Android.
          break;
        default:
          throw new IllegalArgumentException("Unknown WebView setting: " + key);
      }
    }
  }

  private void updateJsMode(int mode) {
    switch (mode) {
      case 0: // disabled
        webView.getSettings().setJavaScriptEnabled(false);
        break;
      case 1: // unrestricted
        webView.getSettings().setJavaScriptEnabled(true);
        break;
      default:
        throw new IllegalArgumentException("Trying to set unknown JavaScript mode: " + mode);
    }
  }

  private void updateAutoMediaPlaybackPolicy(int mode) {
    // This is the index of the AutoMediaPlaybackPolicy enum, index 1 is always_allow, for all
    // other values we require a user gesture.
    boolean requireUserGesture = mode != 1;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      webView.getSettings().setMediaPlaybackRequiresUserGesture(requireUserGesture);
    }
  }

  private void registerJavaScriptChannelNames(List<String> channelNames) {
    for (String channelName : channelNames) {
      webView.addJavascriptInterface(
              new JavaScriptChannel(methodChannel, channelName, platformThreadHandler), channelName);
    }
  }

  private void updateUserAgent(String userAgent) {
    webView.getSettings().setUserAgentString(userAgent);
  }

  @Override
  public void dispose() {
    methodChannel.setMethodCallHandler(null);
    if (webView instanceof InputAwareWebView) {
      ((InputAwareWebView) webView).dispose();
    }
    webView.destroy();
  }


  private void openImageChooserActivity() {
    Log.v(TAG, "openImageChooserActivity");
    Intent intent1 = new Intent(Intent.ACTION_PICK, null);
    intent1.setDataAndType(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
    Intent chooser = new Intent(Intent.ACTION_CHOOSER);
    chooser.putExtra(Intent.EXTRA_TITLE, WebViewFlutterPlugin.activity.getString(R.string.select_picture));
    chooser.putExtra(Intent.EXTRA_INTENT,intent1);

    if (WebViewFlutterPlugin.activity != null){
      WebViewFlutterPlugin.activity.startActivityForResult(chooser, FILE_CHOOSER_RESULT_CODE);
    } else {
      Log.v(TAG, "activity is null");
    }
  }

  private void takePhotoOrOpenGallery() {
    if (WebViewFlutterPlugin.activity==null||!FileUtil.checkSDcard(WebViewFlutterPlugin.activity)) {
      return;
    }

    openImageChooserActivity();

//    String[] selectPicTypeStr = {WebViewFlutterPlugin.activity.getString(R.string.take_photo),
//            WebViewFlutterPlugin.activity.getString(R.string.photo_library)};
//    new AlertDialog.Builder(WebViewFlutterPlugin.activity)
//            .setOnCancelListener(new ReOnCancelListener())
//            .setItems(selectPicTypeStr,
//                    new DialogInterface.OnClickListener() {
//                      @Override
//                      public void onClick(DialogInterface dialog, int which) {
//                        switch (which) {
//                          // 相机拍摄
//                          case 0:
//                            openCamera();
//                            break;
//                          // 手机相册
//                          case 1:
//                            openImageChooserActivity();
//                            break;
//                          default:
//                            break;
//                        }
//                      }
//                    }).show();
  }

  /**
   * Check if the calling context has a set of permissions.
   *
   * @param context the calling context.
   * @param perms   one ore more permissions, such as {@link Manifest.permission#CAMERA}.
   * @return true if all permissions are already granted, false if at least one permission is not
   * yet granted.
   * @see Manifest.permission
   */
  public static boolean hasPermissions(@NonNull Context context,
                                       @Size(min = 1) @NonNull String... perms) {
    // Always return true for SDK < M, let the system deal with the permissions
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      Log.w(TAG, "hasPermissions: API version < M, returning true by default");

      // DANGER ZONE!!! Changing this will break the library.
      return true;
    }

    // Null context may be passed if we have detected Low API (less than M) so getting
    // to this point with a null context should not be possible.
    if (context == null) {
      throw new IllegalArgumentException("Can't check permissions for null context");
    }

    for (String perm : perms) {
      if (ContextCompat.checkSelfPermission(context, perm)
              != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }

    return true;
  }

  /**
   * 打开照相机
   */
  private void openCamera() {
    if (hasPermissions(WebViewFlutterPlugin.activity, perms)) {
      try {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // 给目标应用一个临时授权
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        cameraUri = FileProvider.getUriForFile(WebViewFlutterPlugin.activity, WebViewFlutterPlugin.activity.getPackageName() + ".fileprovider", FileUtil.createImageFile());
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        WebViewFlutterPlugin.activity.startActivityForResult(intent, REQUEST_CAMERA);
      }catch (Exception e) {
        Toast.makeText(WebViewFlutterPlugin.activity,e.getMessage(),Toast.LENGTH_SHORT).show();
        if (uploadMessageAboveL != null) {
          uploadMessageAboveL.onReceiveValue(null);
          uploadMessageAboveL=null;
        }
      }
    } else {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        ActivityCompat.requestPermissions(WebViewFlutterPlugin.activity,perms, REQUEST_CAMERA);
      }
    }
  }

  /**
   * dialog监听类
   */
  private class ReOnCancelListener implements DialogInterface.OnCancelListener {
    @Override
    public void onCancel(DialogInterface dialogInterface) {
      if (uploadMessage != null) {
        uploadMessage.onReceiveValue(null);
        uploadMessage = null;
      }

      if (uploadMessageAboveL != null) {
        uploadMessageAboveL.onReceiveValue(null);
        uploadMessageAboveL = null;
      }
    }
  }

  public boolean requestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == REQUEST_CAMERA) {
      if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        openCamera();
      } else {
        Toast.makeText(WebViewFlutterPlugin.activity, WebViewFlutterPlugin.activity.getString(R.string.take_pic_need_permission), Toast.LENGTH_SHORT).show();
        if (uploadMessage != null) {
          uploadMessage.onReceiveValue(null);
          uploadMessage = null;
        }
        if (uploadMessageAboveL != null) {
          uploadMessageAboveL.onReceiveValue(null);
          uploadMessageAboveL = null;
        }
      }
    }
    return false;
  }

  public boolean activityResult(int requestCode, int resultCode, Intent data) {
    Log.v(TAG, "activityResult: " );
    if (null == uploadMessage && null == uploadMessageAboveL) {
      return false;
    }
    Uri result = null;
    if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
      result = cameraUri;
    }
    if (requestCode == FILE_CHOOSER_RESULT_CODE) {
      result = data == null || resultCode != RESULT_OK ? null : data.getData();
    }
    if (uploadMessageAboveL != null) {
      onActivityResultAboveL(requestCode, resultCode, data);
    } else if (uploadMessage != null && result != null) {
      uploadMessage.onReceiveValue(result);
      uploadMessage = null;
    }
    return false;
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void onActivityResultAboveL(int requestCode, int resultCode, Intent intent) {
    if (requestCode != FILE_CHOOSER_RESULT_CODE && requestCode != REQUEST_CAMERA || uploadMessageAboveL == null) {
      return;
    }
    Uri[] results = null;
    if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
      results = new Uri[]{cameraUri};
    }

    if (requestCode == FILE_CHOOSER_RESULT_CODE && resultCode == Activity.RESULT_OK) {
      if (intent != null) {
        String dataString = intent.getDataString();
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
          results = new Uri[clipData.getItemCount()];
          for (int i = 0; i < clipData.getItemCount(); i++) {
            ClipData.Item item = clipData.getItemAt(i);
            results[i] = item.getUri();
          }
        }
        if (dataString != null) {
          results = new Uri[]{Uri.parse(dataString)};
        }
      }
    }
    uploadMessageAboveL.onReceiveValue(results);
    uploadMessageAboveL = null;
  }
}

