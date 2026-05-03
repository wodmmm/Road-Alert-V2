import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class GarminBeaconApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        return [ new GarminBeaconView() ];
    }
}

function getApp() as GarminBeaconApp {
    return Application.getApp() as GarminBeaconApp;
}