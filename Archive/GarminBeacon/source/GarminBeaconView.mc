import Toybox.Activity;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

class GarminBeaconView extends WatchUi.SimpleDataField {

    private var _lastLat  as Float = 0.0f;
    private var _lastLon  as Float = 0.0f;
    private var _hasPos   as Boolean = false;
    private var _status   as String = "No GPS";
    private var _ticks    as Number = 0;
    private var _interval as Number = 25;

    function initialize() {
        SimpleDataField.initialize();
        label = "Beacon";
    }

    function compute(info as Activity.Info) as Numeric or Duration or String or Null {
        // Grab GPS every second
        if (info has :currentLocation && info.currentLocation != null) {
            var coords = info.currentLocation.toDegrees();
            _lastLat = coords[0].toFloat();
            _lastLon = coords[1].toFloat();
            _hasPos  = true;
            _status  = "GPS OK";
        }

        // Send every 25 seconds
        _ticks++;
        if (_ticks >= _interval && _hasPos) {
            _ticks = 0;
            sendMessage();
        }

        return _status;
    }

    function sendMessage() as Void {
        // Send as a simple list — lightweight over BLE
        var payload = [_lastLat, _lastLon];

        Communications.transmitMessage(
            payload,
            method(:onSent)
        );
        _status = "Sending...";
    }

    function onSent(result as Communications.ConnectionListener.Result) as Void {
        if (result == Communications.ConnectionListener.RESULT_SUCCESS) {
            _status = "OK";
        } else {
            _status = "Err:" + result;
        }
    }
}