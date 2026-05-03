import React, { useState, useEffect } from 'react';
import { Bluetooth, BluetoothOff, MessageSquare, Plus, RefreshCw, Send, ShieldCheck, Trash2 } from 'lucide-react';

const UART_SERVICE_UUID = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const UART_RX_CHAR_UUID = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
const UART_TX_CHAR_UUID = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';

interface Group {
  id: string;
  name: string;
  psk: string;
}

interface Message {
  id: string;
  group: string;
  text: string;
  timestamp: string;
}

const CompanionDash: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const [device, setDevice] = useState<any>(null);
  const [rxChar, setRxChar] = useState<any>(null);
  const [userName, setUserName] = useState<string>('User');
  const [groups, setGroups] = useState<Group[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [activeGroup, setActiveGroup] = useState<string>('Public');
  const [inputText, setInputText] = useState('');
  const [isAddingGroup, setIsAddingGroup] = useState(false);
  const [newGroupName, setNewGroupName] = useState('');
  const [newGroupPSK, setNewGroupPSK] = useState('');

  // Load data from localStorage
  useEffect(() => {
    const savedGroups = localStorage.getItem('ra_groups');
    const savedName = localStorage.getItem('ra_user_name');
    
    if (savedGroups) {
      setGroups(JSON.parse(savedGroups));
    } else {
      const defaultGroup = { id: 'public', name: 'Public', psk: '' };
      setGroups([defaultGroup]);
      localStorage.setItem('ra_groups', JSON.stringify([defaultGroup]));
    }

    if (savedName) setUserName(savedName);
  }, []);

  const connectBLE = async () => {
    try {
      const bleDevice = await (navigator as any).bluetooth.requestDevice({
        filters: [{ namePrefix: 'RoadAlert-' }],
        optionalServices: [UART_SERVICE_UUID]
      });

      const server = await bleDevice.gatt?.connect();
      const service = await server?.getPrimaryService(UART_SERVICE_UUID);
      const tx = await service?.getCharacteristic(UART_TX_CHAR_UUID);
      const rx = await service?.getCharacteristic(UART_RX_CHAR_UUID);

      if (tx) {
        await tx.startNotifications();
        tx.addEventListener('characteristicvaluechanged', handleNotifications);
      }

      setDevice(bleDevice);
      if (rx) setRxChar(rx);

      bleDevice.ongattserverdisconnected = () => {
        setDevice(null);
        setRxChar(null);
      };

      // Sync name and groups on connect
      const encoder = new TextEncoder();
      setTimeout(async () => {
        if (rx) {
          await rx.writeValue(encoder.encode(`SETNAME:${userName}`));
          
          // Auto-sync all private groups configured in the app
          for (const g of groups) {
            if (g.id !== 'public') {
              await new Promise(r => setTimeout(r, 250)); // Small delay to avoid BLE buffer overflow
              await rx.writeValue(encoder.encode(`SETGROUP:${g.name}:${g.psk}`));
            }
          }
        }
      }, 500);
    } catch (err) {
      console.error('BLE Connection Error:', err);
    }
  };

  const handleNotifications = (event: Event) => {
    const value = (event.target as any).value;
    if (!value) return;

    // MeshCore serial frame: [len][data...]
    // For simplicity, our beacon now sends [MESHCHAT]: text
    const decoder = new TextDecoder();
    const str = decoder.decode(value);
    
    if (str.startsWith('[MESHCHAT]:')) {
      const text = str.split('[MESHCHAT]:')[1].trim();
      setMessages((prev: Message[]) => [...prev, {
        id: Math.random().toString(36).substr(2, 9),
        group: 'Unknown', // We'll infer group from the beacon later
        text,
        timestamp: new Date().toLocaleTimeString()
      }]);
    }
  };

  const sendMessage = async () => {
    if (!inputText || !rxChar) return;
    try {
      const encoder = new TextEncoder();
      // Format: MSG:[GroupName]:[Text]
      const msg = `MSG:${activeGroup}:${inputText}`;
      await rxChar.writeValue(encoder.encode(msg));
      
      setMessages((prev: Message[]) => [...prev, {
        id: Math.random().toString(36).substr(2, 9),
        group: activeGroup,
        text: inputText,
        timestamp: new Date().toLocaleTimeString() + ' (Me)'
      }]);
      setInputText('');
    } catch (err) {
      console.error('Send Error:', err);
    }
  };

  const deleteGroup = (id: string) => {
    if (id === 'public') return;
    const updated = groups.filter((g: Group) => g.id !== id);
    setGroups(updated);
    localStorage.setItem('ra_groups', JSON.stringify(updated));
    if (activeGroup === id) setActiveGroup('Public');
  };

  const syncGroup = async (group: Group) => {
    if (!rxChar || group.id === 'public') return;
    try {
      const encoder = new TextEncoder();
      const cmd = `SETGROUP:${group.name}:${group.psk}`;
      await rxChar.writeValue(encoder.encode(cmd));
      console.log(`Synced group ${group.name} to hardware`);
    } catch (err) {
      console.error('Sync Error:', err);
    }
  };

  const addGroup = async () => {
    if (!newGroupName) return;
    const newGroup = { id: Date.now().toString(), name: newGroupName, psk: newGroupPSK };
    const updated = [...groups, newGroup];
    setGroups(updated);
    localStorage.setItem('ra_groups', JSON.stringify(updated));
    setIsAddingGroup(false);
    
    // Auto-sync if connected
    if (rxChar) {
      await syncGroup(newGroup);
    }
    
    setNewGroupName('');
    setNewGroupPSK('');
  };

  return (
    <div className="companion-overlay">
      <div className="companion-header">
        <div className="flex items-center gap-2">
          <ShieldCheck size={20} className="text-blue-400" />
          <div className="user-settings">
            <input 
              className="name-input"
              value={userName} 
              onChange={e => {
                const n = e.target.value.substring(0, 20);
                setUserName(n);
                localStorage.setItem('ra_user_name', n);
                if (rxChar) {
                  const encoder = new TextEncoder();
                  rxChar.writeValue(encoder.encode(`SETNAME:${n}`));
                }
              }}
              placeholder="My Name"
            />
          </div>
        </div>
        <button onClick={onClose} className="close-btn">✕</button>
      </div>

      <div className="companion-body">
        <div className="ble-status-bar">
          {device ? (
            <div className="connected-status">
              <Bluetooth size={16} className="text-green-400" />
              <span>Connected: {device.name}</span>
            </div>
          ) : (
            <button className="connect-btn" onClick={connectBLE}>
              <BluetoothOff size={16} />
              Connect Beacon
            </button>
          )}
        </div>

        <div className="group-list">
          <h3>My Groups</h3>
          {groups.map((g: Group) => (
            <div 
              key={g.id} 
              className={`group-chip ${activeGroup === g.name ? 'active' : ''}`}
              onClick={() => setActiveGroup(g.name)}
            >
              <MessageSquare size={14} />
              {g.name}
              <div className="flex gap-1 ml-2">
                {g.id !== 'public' && device && (
                  <RefreshCw 
                    size={14} 
                    className="hover:text-blue-400 cursor-pointer" 
                    onClick={(e) => {
                      e.stopPropagation();
                      syncGroup(g);
                    }}
                  />
                )}
                {g.id !== 'public' && (
                  <Trash2 
                    size={14} 
                    className="hover:text-red-400 cursor-pointer" 
                    onClick={(e) => {
                      e.stopPropagation();
                      deleteGroup(g.id);
                    }}
                  />
                )}
              </div>
            </div>
          ))}
          <button className="add-group-btn" onClick={() => setIsAddingGroup(true)}>
            <Plus size={16} />
          </button>
        </div>

        <div className="chat-window">
          {messages.filter((m: Message) => m.group === activeGroup || m.group === 'Unknown').length === 0 ? (
            <div className="empty-chat">No messages yet.</div>
          ) : (
            messages.map((m: Message) => (
              <div key={m.id} className="chat-bubble">
                <span className="timestamp">{m.timestamp}</span>
                <p>{m.text}</p>
              </div>
            ))
          )}
        </div>

        <div className="chat-input-area">
          <input 
            type="text" 
            placeholder={device ? "Type a message..." : "Connect beacon to chat"} 
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            disabled={!device}
          />
          <button onClick={sendMessage} disabled={!device}>
            <Send size={18} />
          </button>
        </div>
      </div>

      {isAddingGroup && (
        <div className="modal-overlay">
          <div className="modal">
            <h3>Install New Group</h3>
            <input 
              placeholder="Group Name" 
              value={newGroupName} 
              onChange={e => setNewGroupName(e.target.value)}
            />
            <input 
              placeholder="PSK (Secret Hex)" 
              value={newGroupPSK} 
              onChange={e => setNewGroupPSK(e.target.value)}
            />
            <div className="modal-actions">
              <button onClick={() => setIsAddingGroup(false)}>Cancel</button>
              <button className="primary" onClick={addGroup}>Install</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default CompanionDash;
