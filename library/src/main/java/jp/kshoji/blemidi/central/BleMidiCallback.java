package jp.kshoji.blemidi.central;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jp.kshoji.blemidi.device.MidiInputDevice;
import jp.kshoji.blemidi.device.MidiOutputDevice;
import jp.kshoji.blemidi.listener.OnMidiDeviceAttachedListener;
import jp.kshoji.blemidi.listener.OnMidiDeviceDetachedListener;
import jp.kshoji.blemidi.listener.OnMidiInputEventListener;
import jp.kshoji.blemidi.util.BleMidiDeviceUtils;
import jp.kshoji.blemidi.util.BleUuidUtils;
import jp.kshoji.blemidi.util.Constants;
import jp.kshoji.blemidi.util.BleMidiParser;

/**
 * BluetoothGattCallback implementation for BLE MIDI devices.
 *
 * @author K.Shoji
 */
public final class BleMidiCallback extends BluetoothGattCallback {
    final Map<String, Set<MidiInputDevice>> midiInputDevicesMap = new HashMap<>();
    final Map<String, Set<MidiOutputDevice>> midiOutputDevicesMap = new HashMap<>();
    final Map<String, BluetoothGatt> deviceAddressGattMap = new HashMap<>();
    final Context context;
    final Handler handler;

    OnMidiDeviceAttachedListener midiDeviceAttachedListener;
    OnMidiDeviceDetachedListener midiDeviceDetachedListener;

    /**
     * Constructor
     *
     * @param context the context
     */
    public BleMidiCallback(final Context context) {
        super();
        this.context = context;
        this.handler = new Handler(context.getMainLooper());
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        // In this method, the `status` parameter shall be ignored.
        // so, look `newState` parameter only.

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (gatt.discoverServices()) {
                // successfully started discovering
            } else {
                // already disconnected
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            String gattDeviceAddress = gatt.getDevice().getAddress();

            Set<String> addressToRemove = new HashSet<>();
            // invoke event listeners
            synchronized (midiInputDevicesMap) {
                Set<MidiInputDevice> midiInputDevices = midiInputDevicesMap.get(gattDeviceAddress);
                if (midiInputDevices != null) {
                    for (MidiInputDevice midiInputDevice : midiInputDevices) {
                        midiInputDevice.setOnMidiInputEventListener(null);

                        if (midiDeviceDetachedListener != null) {
                            midiDeviceDetachedListener.onMidiInputDeviceDetached(midiInputDevice);
                        }

                        addressToRemove.add(((InternalMidiInputDevice)midiInputDevice).getDeviceAddress());
                    }
                    midiInputDevices.clear();
                    midiInputDevicesMap.remove(gattDeviceAddress);
                }
            }

            synchronized (midiOutputDevicesMap) {
                Set<MidiOutputDevice> midiOutputDevices = midiOutputDevicesMap.get(gattDeviceAddress);
                if (midiOutputDevices != null) {
                    for (MidiOutputDevice midiOutputDevice : midiOutputDevices) {
                        if (midiDeviceDetachedListener != null) {
                            midiDeviceDetachedListener.onMidiOutputDeviceDetached(midiOutputDevice);
                        }

                        addressToRemove.add(((InternalMidiOutputDevice)midiOutputDevice).getDeviceAddress());
                    }
                    midiOutputDevices.clear();
                    midiOutputDevicesMap.remove(gattDeviceAddress);
                }
            }

            synchronized (deviceAddressGattMap) {
                for (String address : addressToRemove) {
                    deviceAddressGattMap.remove(address);
                }
            }

            gatt.close();
        }
    }

    @SuppressLint("NewApi")
    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);

        if (status != BluetoothGatt.GATT_SUCCESS) {
            return;
        }

        final String gattDeviceAddress = gatt.getDevice().getAddress();

        // find MIDI Input device
        if (midiInputDevicesMap.containsKey(gattDeviceAddress)) {
            synchronized (midiInputDevicesMap) {
                Set<MidiInputDevice> midiInputDevices = midiInputDevicesMap.get(gattDeviceAddress);
                for (MidiInputDevice midiInputDevice : midiInputDevices) {
                    midiInputDevice.setOnMidiInputEventListener(null);
                }
                midiInputDevicesMap.remove(gattDeviceAddress);
            }
        }

        MidiInputDevice midiInputDevice = null;
        try {
            midiInputDevice = new InternalMidiInputDevice(context, gatt);
        } catch (IllegalArgumentException iae) {
            Log.i(Constants.TAG, iae.getMessage());
        }
        if (midiInputDevice != null) {
            synchronized (midiInputDevicesMap) {
                Set<MidiInputDevice> midiInputDevices = midiInputDevicesMap.get(gattDeviceAddress);
                if (midiInputDevices == null) {
                    midiInputDevices = new HashSet<>();
                    midiInputDevicesMap.put(gattDeviceAddress, midiInputDevices);
                }

                midiInputDevices.add(midiInputDevice);
            }

            // don't notify if the same device already connected
            if (!deviceAddressGattMap.containsKey(gattDeviceAddress)) {
                if (midiDeviceAttachedListener != null) {
                    midiDeviceAttachedListener.onMidiInputDeviceAttached(midiInputDevice);
                }
            }
        }

        // find MIDI Output device
        if (midiOutputDevicesMap.containsKey(gattDeviceAddress)) {
            synchronized (midiOutputDevicesMap) {
                midiOutputDevicesMap.remove(gattDeviceAddress);
            }
        }

        MidiOutputDevice midiOutputDevice = null;
        try {
            midiOutputDevice = new InternalMidiOutputDevice(context, gatt);
        } catch (IllegalArgumentException iae) {
            Log.i(Constants.TAG, iae.getMessage());
        }
        if (midiOutputDevice != null) {
            synchronized (midiOutputDevicesMap) {
                Set<MidiOutputDevice> midiOutputDevices = midiOutputDevicesMap.get(gattDeviceAddress);
                if (midiOutputDevices == null) {
                    midiOutputDevices = new HashSet<>();
                    midiOutputDevicesMap.put(gattDeviceAddress, midiOutputDevices);
                }

                midiOutputDevices.add(midiOutputDevice);
            }

            // don't notify if the same device already connected
            if (!deviceAddressGattMap.containsKey(gattDeviceAddress)) {
                if (midiDeviceAttachedListener != null) {
                    midiDeviceAttachedListener.onMidiOutputDeviceAttached(midiOutputDevice);
                }
            }
        }

        if (midiInputDevice != null || midiOutputDevice != null) {
            synchronized (deviceAddressGattMap) {
                deviceAddressGattMap.put(gattDeviceAddress, gatt);
            }

            // Create bond and configure Gatt, if this is BLE MIDI device
            BluetoothDevice bluetoothDevice = gatt.getDevice();
            if (bluetoothDevice.getBondState() == BluetoothDevice.BOND_NONE) {
                bluetoothDevice.createBond();
                bluetoothDevice.setPairingConfirmation(true);

                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                context.registerReceiver(new BondingBroadcastReceiver(midiInputDevice, midiOutputDevice), filter);
            } else {
                if (midiInputDevice != null) {
                    ((InternalMidiInputDevice)midiInputDevice).configureAsCentralDevice();
                }
                if (midiOutputDevice != null) {
                    ((InternalMidiOutputDevice)midiOutputDevice).configureAsCentralDevice();
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // default is 23: maximum data length is 20 bytes
                // max is 512: maximum data length is 509 bytes
                gatt.requestMtu(23); // default value

                // Set the connection priority to high(for low latency)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            }
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        Set<MidiInputDevice> midiInputDevices = midiInputDevicesMap.get(gatt.getDevice().getAddress());
        for (MidiInputDevice midiInputDevice : midiInputDevices) {
            ((InternalMidiInputDevice)midiInputDevice).incomingData(characteristic.getValue());
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);

        if (descriptor != null) {
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, descriptor.getValue())) {
                gatt.setCharacteristicNotification(descriptor.getCharacteristic(), true);
            }
        }
    }

    /**
     * Disconnect the specified device
     * @param midiInputDevice the device
     */
    void disconnectDevice(MidiInputDevice midiInputDevice) {
        if (!(midiInputDevice instanceof InternalMidiInputDevice)) {
            return;
        }

        disconnectByDeviceAddress(((InternalMidiInputDevice) midiInputDevice).getDeviceAddress());
    }

    /**
     * Disconnect the specified device
     * @param midiOutputDevice the device
     */
    void disconnectDevice(MidiOutputDevice midiOutputDevice) {
        if (!(midiOutputDevice instanceof InternalMidiOutputDevice)) {
            return;
        }

        disconnectByDeviceAddress(((InternalMidiOutputDevice) midiOutputDevice).getDeviceAddress());
    }

    /**
     * Disconnects the device by its address
     * @param deviceAddress the device address from {@link android.bluetooth.BluetoothGatt}
     */
    private void disconnectByDeviceAddress(String deviceAddress) {
        synchronized (deviceAddressGattMap) {
            BluetoothGatt bluetoothGatt = deviceAddressGattMap.get(deviceAddress);
            if (bluetoothGatt != null) {
                bluetoothGatt.close();
            }
            deviceAddressGattMap.remove(deviceAddress);
        }

        synchronized (midiInputDevicesMap) {
            Set<MidiInputDevice> midiInputDevices = midiInputDevicesMap.get(deviceAddress);
            if (midiInputDevices != null) {
                for (MidiInputDevice midiInputDevice : midiInputDevices) {
                    midiInputDevice.setOnMidiInputEventListener(null);

                    if (midiDeviceDetachedListener != null) {
                        midiDeviceDetachedListener.onMidiInputDeviceDetached(midiInputDevice);
                    }
                }
            }
            midiInputDevicesMap.remove(deviceAddress);
        }

        synchronized (midiOutputDevicesMap) {
            if (midiDeviceDetachedListener != null) {
                Set<MidiOutputDevice> midiOutputDevices = midiOutputDevicesMap.get(deviceAddress);
                if (midiOutputDevices != null) {
                    for (MidiOutputDevice outputDevice : midiOutputDevices) {
                        midiDeviceDetachedListener.onMidiOutputDeviceDetached(outputDevice);
                    }
                }
            }
            midiOutputDevicesMap.remove(deviceAddress);
        }
    }

    /**
     * BroadcastReceiver for BLE Bonding
     *
     * @author K.Shoji
     */
    public static class BondingBroadcastReceiver extends BroadcastReceiver {
        final MidiInputDevice midiInputDevice;
        final MidiOutputDevice midiOutputDevice;

        BondingBroadcastReceiver(MidiInputDevice midiInputDevice, MidiOutputDevice midiOutputDevice) {
            this.midiInputDevice = midiInputDevice;
            this.midiOutputDevice = midiOutputDevice;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);

                if (state == BluetoothDevice.BOND_BONDED) {
                    // successfully bonded
                    context.unregisterReceiver(this);

                    ((InternalMidiInputDevice)midiInputDevice).configureAsCentralDevice();
                    ((InternalMidiOutputDevice)midiOutputDevice).configureAsCentralDevice();
                }
            }
        }
    }

    /**
     * Obtains connected input devices
     * @return Set of {@link jp.kshoji.blemidi.device.MidiInputDevice}
     */
    public Set<MidiInputDevice> getMidiInputDevices() {
        Collection<Set<MidiInputDevice>> values = midiInputDevicesMap.values();

        Set<MidiInputDevice> result = new HashSet<>();
        for (Set<MidiInputDevice> value: values) {
            result.addAll(value);
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Obtains connected output devices
     * @return Set of {@link jp.kshoji.blemidi.device.MidiOutputDevice}
     */
    public Set<MidiOutputDevice> getMidiOutputDevices() {
        Collection<Set<MidiOutputDevice>> values = midiOutputDevicesMap.values();

        Set<MidiOutputDevice> result = new HashSet<>();
        for (Set<MidiOutputDevice> value: values) {
            result.addAll(value);
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Set the event listener to listen the Device attachment event
     * @param midiDeviceAttachedListener the listener
     */
    public void setOnMidiDeviceAttachedListener(OnMidiDeviceAttachedListener midiDeviceAttachedListener) {
        this.midiDeviceAttachedListener = midiDeviceAttachedListener;
    }

    /**
     * Set the event listener to listen the Device attachment event
     * @param midiDeviceDetachedListener the listener
     */
    public void setOnMidiDeviceDetachedListener(OnMidiDeviceDetachedListener midiDeviceDetachedListener) {
        this.midiDeviceDetachedListener = midiDeviceDetachedListener;
    }

    /**
     * MidiInputDevice for Central
     * @author K.Shoji
     */
    private final class InternalMidiInputDevice extends MidiInputDevice {
        private final BluetoothGatt bluetoothGatt;
        private final BluetoothGattCharacteristic midiInputCharacteristic;

        private final BleMidiParser midiParser = new BleMidiParser(this);

        /**
         * Constructor for Central
         *
         * @param context the context
         * @param bluetoothGatt the gatt of device
         * @throws IllegalArgumentException if specified gatt doesn't contain BLE MIDI service
         */
        public InternalMidiInputDevice(final Context context, final BluetoothGatt bluetoothGatt) throws IllegalArgumentException {
            super();
            this.bluetoothGatt = bluetoothGatt;

            BluetoothGattService midiService = BleMidiDeviceUtils.getMidiService(context, bluetoothGatt);
            if (midiService == null) {
                List<UUID> uuidList = new ArrayList<>();
                for (BluetoothGattService service : bluetoothGatt.getServices()) {
                    uuidList.add(service.getUuid());
                }
                throw new IllegalArgumentException("MIDI GattService not found from '" + bluetoothGatt.getDevice().getName() + "'. Service UUIDs:" + Arrays.toString(uuidList.toArray()));
            }

            midiInputCharacteristic = BleMidiDeviceUtils.getMidiInputCharacteristic(context, midiService);
            if (midiInputCharacteristic == null) {
                throw new IllegalArgumentException("MIDI Input GattCharacteristic not found. Service UUID:" + midiService.getUuid());
            }
        }

        /**
         * Configure the device as BLE Central
         */
        public void configureAsCentralDevice() {
            bluetoothGatt.setCharacteristicNotification(midiInputCharacteristic, true);

            List<BluetoothGattDescriptor> descriptors = midiInputCharacteristic.getDescriptors();
            for (BluetoothGattDescriptor descriptor : descriptors) {
                if (BleUuidUtils.matches(BleUuidUtils.fromShortValue(0x2902), descriptor.getUuid())) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    bluetoothGatt.writeDescriptor(descriptor);
                }
            }

            bluetoothGatt.readCharacteristic(midiInputCharacteristic);
        }

        @Override
        public void setOnMidiInputEventListener(OnMidiInputEventListener midiInputEventListener) {
            midiParser.setMidiInputEventListener(midiInputEventListener);
        }

        @Override
        public String getDeviceName() {
            return bluetoothGatt.getDevice().getName();
        }

        /**
         * Obtains device address
         * @return device address
         */
        public String getDeviceAddress() {
            return bluetoothGatt.getDevice().getAddress();
        }

        void incomingData(byte[] data) {
            midiParser.parse(data);
        }
    }

    /**
     * MidiOutputDevice for Central
     * @author K.Shoji
     */
    private final class InternalMidiOutputDevice extends MidiOutputDevice {
        private final BluetoothGatt bluetoothGatt;
        private final BluetoothGattCharacteristic midiOutputCharacteristic;

        /**
         * Constructor for Central
         *
         * @param context the context
         * @param bluetoothGatt the gatt of device
         * @throws IllegalArgumentException if specified gatt doesn't contain BLE MIDI service
         */
        public InternalMidiOutputDevice(final Context context, final BluetoothGatt bluetoothGatt) throws IllegalArgumentException {
            super();
            this.bluetoothGatt = bluetoothGatt;

            BluetoothGattService midiService = BleMidiDeviceUtils.getMidiService(context, bluetoothGatt);
            if (midiService == null) {
                List<UUID> uuidList = new ArrayList<>();
                for (BluetoothGattService service : bluetoothGatt.getServices()) {
                    uuidList.add(service.getUuid());
                }
                throw new IllegalArgumentException("MIDI GattService not found from '" + bluetoothGatt.getDevice().getName() + "'. Service UUIDs:" + Arrays.toString(uuidList.toArray()));
            }

            midiOutputCharacteristic = BleMidiDeviceUtils.getMidiOutputCharacteristic(context, midiService);
            if (midiOutputCharacteristic == null) {
                throw new IllegalArgumentException("MIDI Output GattCharacteristic not found. Service UUID:" + midiService.getUuid());
            }
        }

        /**
         * Configure the device as BLE Central
         */
        public void configureAsCentralDevice() {
            midiOutputCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        }

        @Override
        public void transferData(byte[] writeBuffer) {
            midiOutputCharacteristic.setValue(writeBuffer);

            try {
                bluetoothGatt.writeCharacteristic(midiOutputCharacteristic);
            } catch (Throwable ignored) {
                // android.os.DeadObjectException will be thrown
                // ignore it
            }
        }

        @Override
        public String getDeviceName() {
            return bluetoothGatt.getDevice().getName();
        }

        /**
         * Obtains device address
         * @return device address
         */
        public String getDeviceAddress() {
            return bluetoothGatt.getDevice().getAddress();
        }
    }
}
