import Toybox.Time;
import Toybox.Activity;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

class GarminBeaconView extends WatchUi.SimpleDataField {

    private var _timer   as Timer.Timer;
    private var _lastLat as Float = 0.0f;
    private var _lastLon as Float = 0.0f;
    private var _hasPos  as Boolean = false;
    private var _status  as String = "No GPS";

    function initialize() {
        SimpleDataField.initialize();
        label = "Beacon";
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 25000, true);
    }

    // compute() is called every second by the device — we grab GPS here
    function compute(info as Activity.Info) as Numeric or Duration or String or Null {
        if (info has :currentLocation && info.currentLocation != null) {
            var coords = info.currentLocation.toDegrees();
            _lastLat = coords[0].toFloat();
            _lastLon = coords[1].toFloat();
            _hasPos  = true;
        }
        return _status;
    }

    function onTick() as Void {
        if (!_hasPos) {
            _status = "No GPS";
            return;
        }

        var payload = {
            "type" => "CYC",
            "lat"  => _lastLat,
            "lon"  => _lastLon
        };

        Communications.makeWebRequest(
            "http://localhost:8080/beacon",
            payload,
            {
                :method       => Communications.HTTP_REQUEST_METHOD_POST,
                :headers      => {
                    "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON
                },
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            method(:onResponse)
        );

        _status = "Sending...";
    }

    function onResponse(code as Number, data as Dictionary?) as Void {
        if (code == 200) {
            _status = "OK";
        } else {
            _status = "Err:" + code;
        }
    }
}