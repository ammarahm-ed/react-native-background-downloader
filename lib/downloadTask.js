import { NativeModules } from "react-native";
const { RNBackgroundDownloader } = NativeModules;

function validateHandler(handler) {
  if (!(typeof handler === "function")) {
    throw new TypeError(
      `[RNBackgroundDownloader] expected argument to be a function, got: ${typeof handler}`
    );
  }
}
export default class DownloadTask {
  state = "PENDING";
  percent = 0;
  bytesWritten = 0;
  totalBytes = 0;

  constructor(taskInfo) {
    if (typeof taskInfo === "string") {
      this.id = taskInfo;
    } else {
      this.id = taskInfo.id;
      this.percent = taskInfo.percent;
      this.bytesWritten = taskInfo.bytesWritten;
      this.totalBytes = taskInfo.totalBytes;
    }
  }

  begin(handler) {
    validateHandler(handler);
    this._beginHandler = handler;
    return this;
  }
  added(handler) {
    validateHandler(handler);
    this._addHandler = handler;
    return this;
  }
  progress(handler) {
    validateHandler(handler);
    this._progressHandler = handler;
    return this;
  }

  done(handler) {
    validateHandler(handler);
    this._doneHandler = handler;
    return this;
  }

  error(handler) {
    validateHandler(handler);
    this._errorHandler = handler;
    return this;
  }

  paused(handler) {
    validateHandler(handler);
    this._pausedHandler = handler;
    return this;
  }
  resumed(handler) {
    validateHandler(handler);
    this._resumedHandler = handler;
    return this;
  }
  cancelled(handler) {
    validateHandler(handler);
    this._cancelledHandler = handler;
    return this;
  }
  queued(handler) {
    validateHandler(handler);
    this._queuedHandler = handler;
    return this;
  }
  waitingForNetwork(handler) {
    validateHandler(handler);
    this._waitingForNetworkHandler = handler;
    return this;
  }
  _onAdded(expectedBytes) {
    this.state = "PENDING";
    if (this._addHandler) {
      this._addHandler(expectedBytes);
    }
  }
  _onPaused() {
    this.state = "PAUSED";
    if (this._pausedHandler) {
      this._pausedHandler();
    }
  }
  _onResumed() {
    this.state = "DOWNLOADING";
    if (this._resumedHandler) {
      this._resumedHandler();
    }
  }
  _onCancelled() {
    this.state = "STOPPED";
    if (this._cancelledHandler) {
      this._cancelledHandler();
    }
  }
  _onQueued(waitingForNetwork) {
    this.state = "QUEUED";
    if (this._queuedHandler) {
      this._queuedHandler(waitingForNetwork);
    }
  }
  _onWaitingForNetwork() {
    this.state = "WAITING";
    if (this._waitingForNetworkHandler) {
      this._waitingForNetworkHandler();
    }
  }

  _onBegin(expectedBytes) {
    this.state = "DOWNLOADING";
    if (this._beginHandler) {
      this._beginHandler(expectedBytes);
    }
  }

  _onProgress(percent, bytesWritten, totalBytes) {
    this.percent = percent;
    this.bytesWritten = bytesWritten;
    this.totalBytes = totalBytes;
    if (this._progressHandler) {
      this._progressHandler(percent, bytesWritten, totalBytes);
    }
  }

  _onDone() {
    this.state = "DONE";
    if (this._doneHandler) {
      this._doneHandler();
    }
  }

  _onError(error) {
    this.state = "FAILED";
    if (this._errorHandler) {
      this._errorHandler(error);
    }
  }

  pause() {
    this.state = "PAUSED";
    RNBackgroundDownloader.pauseTask(this.id);
  }

  resume() {
    this.state = "DOWNLOADING";
    RNBackgroundDownloader.resumeTask(this.id);
  }

  stop() {
    this.state = "STOPPED";
    RNBackgroundDownloader.stopTask(this.id);
  }

  retry() {
    this.state = "DOWNLOADING";
    RNBackgroundDownloader.retryTask(this.id);
  }

  delete() {
    RNBackgroundDownloader.deleteTask(this.id);
  }
}
