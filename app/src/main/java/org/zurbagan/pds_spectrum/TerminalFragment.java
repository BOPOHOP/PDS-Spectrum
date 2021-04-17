package org.zurbagan.pds_spectrum;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import android.os.Environment;
import java.util.Locale;

import static android.content.Context.MODE_WORLD_READABLE;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private boolean reply_in_progress = false;
    private boolean reply_wanted = false;
    private ByteBuffer reply_buf;
    private ByteBuffer reply_data;

    private String   pds_Serial = "";		// PDS-100G serial number
    private float    pds_Gain = 1f;		    // gain
    private int      pds_Offset = 0;		// Offset (bin)
    private int      pds_Temperature = 0;	// temperature °C
    private int      pds_AcqTime = 0;		// acquisition time (max 999 seconds)
    private float    pds_DoseRate = 0f;		// dose rate in uSv/s
    private float    pds_NeutronR = 0f;		// Neutron rate
    private String   pds_SpDate = "";
    private String   pds_SpTime = "";
    private int      pds_Bins = 0;
    private int      pds_SpectrNo = -1;		// spectrum number (from last to first in memory) [n..1]
    private int      pds_TotalCounts = 0;
    private boolean  print_debug = false;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void sendcmd(String cmd_str, String show_str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            msg = show_str;
            data = ("\002" + cmd_str + "\003").getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
            reply_in_progress = false;
            reply_wanted = true;
            reply_buf = ByteBuffer.allocate(9999*2+200); // max 9999*2byte spectrum + headers
            reply_buf.order(ByteOrder.BIG_ENDIAN);

        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void send(String str) {
//        sendcmd("G18", "get S/N");
//        try
//        {
//            Thread.sleep(500);
//        }
//        catch(InterruptedException ex)
//        {
//            Thread.currentThread().interrupt();
//        }
        try {
            if (pds_SpectrNo < 0) {
                sendcmd("G22000", "CMD: get last spectrum");
            } else {
                sendcmd("G22002", "CMD: get prev spectrum");
            }
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void store_spectrum_file(int[] values) {
//        FileOutputStream file = null;
        String filename;

        PrintStream file = null;
        filename = new String(Environment.getExternalStorageDirectory() +
                "/Download/pds/pds_" + pds_SpDate + "_" + pds_SpTime + ".txt");

        receiveText.append("Spectrum#" + pds_SpectrNo + " Total counts: " +
                pds_TotalCounts + " \n");

        try {
            file = new PrintStream(filename);

//            FORMAT: 2
//            Время модификации данных спектра другие коменты: 2021.03.22 14:27:48 +0200 Counts: 467211, ~cps: 586.212, Time: 797.00 s
//            localtime (как unixtime) записи спектра: 1616416068002
//                    ?: 0
//                    ?: 0
//                    ?: 0
//            время набора: 797.000000
//
//            количество каналов: 8192
//            степень полинома: 2
//            Точка калибровки c1: 1000
//            Точка калибровки e1: 477.315190
//            Точка калибровки c2: 2000
//            Точка калибровки e2: 981.327660
//            Точка калибровки c3: 4000
//            Точка калибровки e3: 2029.447540
//                    <поканальные отсчеты по одной строке на канал>

            file.println("FORMAT: 2");
            file.println("2021.03.22 14:27:48 +0200 Counts: 467211, ~cps: 586.212, Time: 797.00 s");
            file.println("1616416068002");
            file.printf("%d\n%d\n%d\n", 0, 0, 0);
            file.printf("%d\n", pds_AcqTime);
            file.printf("%d\n", pds_Bins);
            file.printf("%d\n", 2);
            file.printf(Locale.US, "%f\n", 100f);
            file.printf(Locale.US, "%f\n", 100f * pds_Gain + pds_Offset);
            file.printf(Locale.US, "%f\n", 500f);
            file.printf(Locale.US, "%f\n", 500f * pds_Gain + pds_Offset);
            file.printf(Locale.US, "%f\n", 1000f);
            file.printf(Locale.US, "%f\n", 1000f * pds_Gain + pds_Offset);
            for (int i = 0; i < pds_Bins; i++) {
                file.printf(Locale.US, "%d\n", values[i]);
            }


            receiveText.append("Spectrum " + pds_SpectrNo + " saved to " +
                    filename + "\n");
        }
        catch(Exception e) {
            receiveText.append("Save to file error(s): " +  e + "\n");
        }
        finally{
            try{
                if(file!=null)
                    file.close();
            }
            catch(Exception e){
                receiveText.append("Save to file error(s): " +  e + "\n");
            }
        }

    }


    private void parse_spectrum (ByteBuffer buffer) {
        try {
            if (buffer.position() >= 51 && pds_Bins < 1) {
                pds_TotalCounts = 0;
                pds_Serial = new String(Arrays.copyOfRange(buffer.array(),
                        4, 12), "ISO-8859-1");
                pds_Gain = (float)Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        12, 15), "ISO-8859-1")) / 100.0f; // gain
                pds_Offset = Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        15, 18), "ISO-8859-1")); // # Offset (bin)
                pds_Temperature = Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        18, 21), "ISO-8859-1")); //        # temperature °C
                pds_AcqTime = Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        21, 24), "ISO-8859-1")); //           # acquisition time (max 999 seconds)
                pds_DoseRate = (float) Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        24, 29), "ISO-8859-1")) / 100.0f; //   # dose rate in uSv/s
                pds_NeutronR = (float) Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        29, 32), "ISO-8859-1")) / 100.0f; //   # Neutron rate

                //# date of spectrum acquisition (20yymmdd)
                pds_SpDate = "20" + new String(Arrays.copyOfRange(buffer.array(),
                        42, 44), "ISO-8859-1") +
                        new String(Arrays.copyOfRange(buffer.array(),
                                40, 42), "ISO-8859-1") +
                        new String(Arrays.copyOfRange(buffer.array(),
                                38, 40), "ISO-8859-1");
                // # time of spectrum acquisition (hhmmss)
                pds_SpTime = new String(Arrays.copyOfRange(buffer.array(),
                        32, 38), "ISO-8859-1");
                pds_Bins = Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        44, 48), "ISO-8859-1"));     //                # number of bins (512 or 1024)
                pds_SpectrNo = Integer.parseInt(new String(Arrays.copyOfRange(buffer.array(),
                        48, 51), "ISO-8859-1")); //          # spectrum number (from last to first in memory) [n..1]
                receiveText.append("Spectrum " + pds_SpectrNo + " " + pds_SpDate + "-" +
                        pds_SpTime + " started with " + pds_Bins + " bins\n");
                receiveText.append(" " +
                        " Gain: " + String.format("%.02f", pds_Gain) +
                        " offset: " + String.format("%d", pds_Offset) +
                        " Temp:" + String.format("%d", pds_Temperature) +
                        " Time: " + String.format("%d sec", pds_AcqTime) +
                        "\n");


            } else if (pds_Bins > 0) {
                int pos = buffer.position();
                if ((buffer.position() - 51) >= pds_Bins * 2 + 3){
                    receiveText.append("all " + pds_Bins + " bins fetched (" +
                            String.format("%d", (buffer.position() - 51) / 2) + ")\n");


                    int[] values = new int[9999] ;

                    reply_wanted = false;
                    reply_in_progress = false;
                    for (int i = 0; i < pds_Bins; i++) {
                        values[i] = ((int)buffer.array()[51+i*2]&0xff) * 256 +
                                ((int)buffer.array()[51+i*2+1]&0xff);
                        pds_TotalCounts += values[i];
                    }
                    values[pds_Bins-1] = 0; // ?? garbage in last bin
                    for (int i = 0; i < pds_Bins; i++) {
//                        receiveText.append(" tr\n");
                        if (print_debug && (i < 50 || i > pds_Bins - 50)) {

                            receiveText.append(" " + i + ": " +
                                String.format("%05d", values[i]) + " " +
                                String.format("%04x", values[i]) +
                                "\n");
                        }
                    }
                    store_spectrum_file(values);
                    receiveText.append("Done for spectrum#" + pds_SpectrNo + " Total counts: " +
                            pds_TotalCounts + "\n");
                } else {
                    receiveText.append(" " + String.format("%d", ((buffer.position() - 51) / 2)) +
                            " of " + pds_Bins + " fetched\n");
                }
                buffer.position(pos);
            }
        } catch (Exception e){
            receiveText.append(" Exception...\n" + e);
            reply_wanted = false;
            reply_in_progress = false;
        }
    }

    private void receive(byte[] data) {
        String stmp;
        if (reply_wanted && ! reply_in_progress) {
            try {
                stmp = new String(data, "ISO-8859-1");
                if (stmp.substring(0,4).equals("\002R18")) {
                    pds_Serial = stmp.substring(22,30);
                    receiveText.append("got pds S/N: " + pds_Serial);
                    reply_wanted = false;
                } else if (stmp.substring(0,4).equals("\002R22")) {
                    reply_buf.put(data);
                    pds_Bins = 0;
                    pds_TotalCounts = 0;
                    if (print_debug) {
                        receiveText.append("r1..." + String.format("%d", reply_buf.position()) + "\n");
                    }
                    reply_in_progress = true;
                    parse_spectrum(reply_buf);
                }
            } catch (Exception e) {
                receiveText.append(" exception" + e + "\n");
//                onSerialIoError(e);
            }
        } else if (reply_wanted && reply_in_progress) {
            reply_buf.put(data);
            if (print_debug) {
                receiveText.append("r2..." + String.format("%d", reply_buf.position()) + "\n");
            }
            parse_spectrum(reply_buf);
        } else if (hexEnabled) {
                receiveText.append(TextUtil.toHexString(data) + '\n');
        } else {
             String msg = new String(data);
             if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg.charAt(0) == '\n') {
                     Editable edt = receiveText.getEditableText();
                     if (edt != null && edt.length() > 1)
                         edt.replace(edt.length() - 2, edt.length(), "");
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
             }
             receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
