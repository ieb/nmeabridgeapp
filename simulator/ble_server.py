"""BLE GATT server using CoreBluetooth directly via PyObjC.

Replaces the `bless` library which has issues with multiple notify
characteristics on macOS. This implementation correctly handles
independent subscriptions and notifications per characteristic.
"""

import asyncio
import logging
import threading
from typing import Callable, Optional

import objc
from Foundation import NSObject, NSData, NSUUID
from CoreBluetooth import (
    CBPeripheralManager,
    CBMutableService,
    CBMutableCharacteristic,
    CBATTRequest,
    CBATTErrorSuccess,
    CBManagerStatePoweredOn,
    CBAdvertisementDataLocalNameKey,
    CBAdvertisementDataServiceUUIDsKey,
    CBCharacteristicPropertyRead,
    CBCharacteristicPropertyWrite,
    CBCharacteristicPropertyWriteWithoutResponse,
    CBCharacteristicPropertyNotify,
    CBAttributePermissionsReadable,
    CBAttributePermissionsWriteable,
)
from libdispatch import dispatch_queue_create, DISPATCH_QUEUE_SERIAL

logger = logging.getLogger("ble_server")

CBUUID = objc.lookUpClass("CBUUID")


class GATTCharacteristic:
    """A GATT characteristic with its current value and subscriber tracking."""

    PROP_READ = CBCharacteristicPropertyRead
    PROP_WRITE = CBCharacteristicPropertyWrite
    PROP_WRITE_NO_RESP = CBCharacteristicPropertyWriteWithoutResponse
    PROP_NOTIFY = CBCharacteristicPropertyNotify

    PERM_READ = CBAttributePermissionsReadable
    PERM_WRITE = CBAttributePermissionsWriteable

    def __init__(self, uuid: str, properties: int, permissions: int):
        self.uuid = uuid.lower()
        self.cb_uuid = CBUUID.UUIDWithString_(uuid)
        self.properties = properties
        self.permissions = permissions
        self.value: bytearray = bytearray()
        self.cb_char: Optional[CBMutableCharacteristic] = None
        self.subscribers: set = set()  # set of CBCentral objects


class GATTService:
    """A GATT service containing characteristics."""

    def __init__(self, uuid: str):
        self.uuid = uuid.lower()
        self.cb_uuid = CBUUID.UUIDWithString_(uuid)
        self.characteristics: dict[str, GATTCharacteristic] = {}

    def add_characteristic(self, char: GATTCharacteristic):
        self.characteristics[char.uuid] = char


class _PeripheralDelegate(NSObject):
    """CBPeripheralManagerDelegate implemented directly in PyObjC."""

    def init(self):
        self = objc.super(_PeripheralDelegate, self).init()
        self._powered_on = threading.Event()
        self._advertising_started = threading.Event()
        self._service_added = threading.Event()
        self._peripheral_manager = None
        self._characteristics_by_cb: dict = {}  # CBCharacteristic hash -> GATTCharacteristic
        self._write_callback: Optional[Callable] = None
        self._connect_callback: Optional[Callable] = None
        self._disconnect_callback: Optional[Callable] = None
        self._client_connected = False
        self._event_loop: Optional[asyncio.AbstractEventLoop] = None
        return self

    def start_manager(self):
        self._peripheral_manager = (
            CBPeripheralManager.alloc().initWithDelegate_queue_(
                self, dispatch_queue_create(b"BLEServer", DISPATCH_QUEUE_SERIAL)
            )
        )
        self._powered_on.wait(timeout=10.0)
        if not self._powered_on.is_set():
            raise RuntimeError("Bluetooth did not power on within 10 seconds")

    # --- CBPeripheralManagerDelegate callbacks ---

    def peripheralManagerDidUpdateState_(self, peripheral_manager):
        state = peripheral_manager.state()
        if state == CBManagerStatePoweredOn:
            logger.debug("Bluetooth powered on")
            self._powered_on.set()
        else:
            logger.debug(f"Bluetooth state changed: {state}")
            self._powered_on.clear()

    def peripheralManager_didAddService_error_(self, pm, service, error):
        if error:
            logger.error(f"Failed to add service: {error}")
        else:
            logger.debug(f"Service added: {service.UUID().UUIDString()}")
        self._service_added.set()

    def peripheralManagerDidStartAdvertising_error_(self, pm, error):
        if error:
            logger.error(f"Failed to start advertising: {error}")
        else:
            logger.debug("Advertising started")
        self._advertising_started.set()

    def peripheralManager_central_didSubscribeToCharacteristic_(
        self, pm, central, characteristic
    ):
        char_hash = hash(characteristic)
        gatt_char = self._characteristics_by_cb.get(char_hash)
        if gatt_char:
            was_empty = len(gatt_char.subscribers) == 0
            gatt_char.subscribers.add(central)
            logger.info(
                f"Central subscribed to {gatt_char.uuid} "
                f"(now {len(gatt_char.subscribers)} subscriber(s))"
            )
            # Notify connect once per connection
            if not self._client_connected and self._connect_callback:
                self._client_connected = True
                self._connect_callback()

    def peripheralManager_central_didUnsubscribeFromCharacteristic_(
        self, pm, central, characteristic
    ):
        char_hash = hash(characteristic)
        gatt_char = self._characteristics_by_cb.get(char_hash)
        if gatt_char:
            gatt_char.subscribers.discard(central)
            logger.info(
                f"Central unsubscribed from {gatt_char.uuid} "
                f"(now {len(gatt_char.subscribers)} subscriber(s))"
            )
            # Check if all characteristics have no subscribers
            all_empty = all(
                len(c.subscribers) == 0
                for c in self._characteristics_by_cb.values()
            )
            if all_empty and self._client_connected:
                self._client_connected = False
                if self._disconnect_callback:
                    self._disconnect_callback()

    def peripheralManager_didReceiveReadRequest_(self, pm, request):
        char_hash = hash(request.characteristic())
        gatt_char = self._characteristics_by_cb.get(char_hash)
        if gatt_char:
            request.setValue_(NSData.dataWithBytes_length_(
                bytes(gatt_char.value), len(gatt_char.value)
            ))
            pm.respondToRequest_withResult_(request, CBATTErrorSuccess)
        else:
            pm.respondToRequest_withResult_(request, 0x0A)  # attribute not found

    def peripheralManager_didReceiveWriteRequests_(self, pm, requests):
        for request in requests:
            char_hash = hash(request.characteristic())
            gatt_char = self._characteristics_by_cb.get(char_hash)
            if gatt_char and self._write_callback:
                value = request.value()
                if value is not None:
                    data = bytes(value)
                    self._write_callback(gatt_char.uuid, data)
        pm.respondToRequest_withResult_(requests[0], CBATTErrorSuccess)

    def peripheralManagerIsReadyToUpdateSubscribers_(self, pm):
        logger.debug("Ready to update subscribers")


class BleGattServer:
    """High-level BLE GATT server using CoreBluetooth directly.

    Usage:
        server = BleGattServer("BoatWatch")
        service = server.add_service("0000AA00-...")
        char = server.add_characteristic(service, "0000AA01-...",
            GATTCharacteristic.PROP_READ | GATTCharacteristic.PROP_NOTIFY,
            GATTCharacteristic.PERM_READ)
        server.on_write = my_callback
        await server.start()

        # Send notifications:
        server.notify(char, bytearray(data))
    """

    def __init__(self, name: str):
        self.name = name
        self.services: dict[str, GATTService] = {}
        self._delegate = _PeripheralDelegate.alloc().init()
        self._delegate.start_manager()
        self.on_write: Optional[Callable[[str, bytes], None]] = None
        self.on_connect: Optional[Callable[[], None]] = None
        self.on_disconnect: Optional[Callable[[], None]] = None

    def add_service(self, uuid: str) -> GATTService:
        service = GATTService(uuid)
        self.services[service.uuid] = service
        return service

    def add_characteristic(
        self,
        service: GATTService,
        uuid: str,
        properties: int,
        permissions: int,
    ) -> GATTCharacteristic:
        char = GATTCharacteristic(uuid, properties, permissions)
        service.add_characteristic(char)
        return char

    async def start(self):
        """Build CoreBluetooth objects, add services, and start advertising."""
        pm = self._delegate._peripheral_manager
        self._delegate._write_callback = self._handle_write
        self._delegate._connect_callback = self._handle_connect
        self._delegate._disconnect_callback = self._handle_disconnect

        for service in self.services.values():
            cb_chars = []
            for char in service.characteristics.values():
                cb_char = CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
                    char.cb_uuid, char.properties, None, char.permissions
                )
                char.cb_char = cb_char
                self._delegate._characteristics_by_cb[hash(cb_char)] = char
                cb_chars.append(cb_char)

            cb_service = CBMutableService.alloc().initWithType_primary_(
                service.cb_uuid, True
            )
            cb_service.setCharacteristics_(cb_chars)

            self._delegate._service_added.clear()
            pm.addService_(cb_service)
            self._delegate._service_added.wait(timeout=5.0)

        # Start advertising
        ad_uuids = [s.cb_uuid for s in self.services.values()]
        ad_data = {
            CBAdvertisementDataLocalNameKey: self.name,
            CBAdvertisementDataServiceUUIDsKey: ad_uuids,
        }
        self._delegate._advertising_started.clear()
        pm.startAdvertising_(ad_data)
        self._delegate._advertising_started.wait(timeout=5.0)

    def notify(self, char: GATTCharacteristic, data: bytearray) -> bool:
        """Send a notification to all subscribers of this characteristic.

        Returns True if the notification was queued, False if the queue is full.
        """
        if not char.subscribers:
            return False

        char.value = data
        ns_data = NSData.dataWithBytes_length_(bytes(data), len(data))
        pm = self._delegate._peripheral_manager

        return pm.updateValue_forCharacteristic_onSubscribedCentrals_(
            ns_data, char.cb_char, None
        )

    def has_subscribers(self, char: GATTCharacteristic) -> bool:
        return len(char.subscribers) > 0

    def _handle_write(self, char_uuid: str, data: bytes):
        if self.on_write:
            self.on_write(char_uuid, data)

    def _handle_connect(self):
        if self.on_connect:
            self.on_connect()

    def _handle_disconnect(self):
        if self.on_disconnect:
            self.on_disconnect()

    async def stop(self):
        pm = self._delegate._peripheral_manager
        pm.stopAdvertising()
        pm.removeAllServices()
