package lejos;

import static lejos.Reference.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarFile;

import addonloader.main.AddonLoader;
import addonloader.main.LoadingStage;
import addonloader.menu.MappedMenu;
import addonloader.menu.MenuEntry;
import addonloader.menu.HookRegistry;
import addonloader.menu.InputMethod;
import addonloader.menu.SimpleMenuEntry;
import addonloader.util.DataSize;
import addonloader.util.LoadingScreen;
import addonloader.util.MenuUtils;
import addonloader.util.StockIcon;
import addonloader.util.Viewer;
import addonloader.util.ui.Icon;
import lejos.utility.Delay;
import lejos.utility.TextMenu;
import lejos.Utils;
import lejos.hardware.Battery;
import lejos.hardware.Bluetooth;
import lejos.hardware.BluetoothException;
import lejos.hardware.BrickFinder;
import lejos.hardware.Button;
import lejos.hardware.Key;
import lejos.hardware.LocalBTDevice;
import lejos.hardware.LocalWifiDevice;
import lejos.hardware.RemoteBTDevice;
import lejos.hardware.Sound;
import lejos.hardware.Wifi;
import lejos.hardware.ev3.LocalEV3;
import lejos.hardware.lcd.Font;
import lejos.hardware.lcd.LCD;
import lejos.hardware.lcd.TextLCD;
import lejos.hardware.port.Port;
import lejos.hardware.port.TachoMotorPort;
import lejos.internal.ev3.EV3IOPort;
import lejos.internal.io.Settings;
import lejos.internal.io.SystemSettings;
import lejos.internal.io.NativeHCI.LocalVersion;
import lejos.remote.ev3.Menu;
import lejos.remote.ev3.RMIRemoteEV3;

@SuppressWarnings({"restriction"})
public class MainMenu implements Menu {	
	
	public static List<String> ips = new ArrayList<String>();
	public static String hostname, panAddress, wlanAddress;
	public static AddonLoader addon_loader;
	public static MainMenu self;
	public static TextLCD lcd;
	public static Viewer default_viewer;
	
	protected static Process program; // the running user program, if any
	protected static String programName; // The name of the running program
	private static final String[] bluetoothVersions = {"1.0b", "1.1", "1.2", "2.0", "2.1", "3.0", "4.0"};
	private static String lejosversion, menuversion;
	private static LocalBTDevice bt;
	private static EchoThread echoIn, echoErr;
	private static boolean suspend = false;
	
	public int timeout;
	protected PANConfig panConfig;
	protected IndicatorThread ind;
	protected InfoBar indiBA;
	protected RConsole rcons;
	protected RemoteMenuThread remoteMenuThread;
	private WaitScreen waitScreen;
	
	/**
	 * @param exit_state should be one of the following:
	 * <table>
	 * 	<tr><td>0: No Exitting / Cancel Exitting</td></tr>
	 * 	<tr><td>1: Soft exit (Terminate menu)</td></tr>
	 * 	<tr><td>2: Restart (Menu restarts itself)</td></tr>
	 * 	<tr><td>3: Shutdown (init 0)</td></tr>
	 * 	<tr><td>4: Reboot (init 6)</td></tr>
	 * </table>
	 */
	public int exit = 0;
	
	public MainMenu()
	{
		ind = new IndicatorThread();
		indiBA = new InfoBar("EV3");
		rcons = new RConsole();
		remoteMenuThread = new RemoteMenuThread();
		waitScreen = WaitScreen.instance;
		panConfig = new PANConfig();
		timeout = 0;
		exit = 0;
	}
	
	public static void main(String[] args) throws Exception
	{
		LoadingScreen loading = new LoadingScreen();
		loading.start("AddonLoader");
		loading.setState("Starting up...", 10);
		File bootLock = new File("/var/run/bootlock");
		while(bootLock.exists()) Delay.msDelay(500); //Wait until fully booted
		LocalEV3.get().getLED().setPattern(1); //Set pattern to green
		
		loading.setState("Init Menu", 20);
		System.setOut(new PrintStream("/var/volatile/log/menu.log"));
		System.setErr(System.out);
		Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler());
		InputMethod.current = new DefaultKeyboard();
		WaitScreen.init();
		MainMenu.default_viewer = new Viewer(Font.getSmallFont());
		MainMenu.addon_loader = new AddonLoader(Reference.LEJOS_HOME + "/config/addons.conf");
		MainMenu.lejosversion = args.length > 1 ? args[1] : "UNKNOWN";
		MainMenu.menuversion = "AF-" + MenuUtils.formatVersion(Reference.API_LEVEL, 3); //Sets the menu version
		MainMenu.hostname = args.length > 0 ? args[0] : "EV3";
		MainMenu.bt = Bluetooth.getLocalDevice();
		MainMenu.lcd = LocalEV3.get().getTextLCD();
		MainMenu.self = new MainMenu();
		loading.setState("Load icons...", 25);
		MainMenu.init_menus();
		loading.setState("Load JAR files", 35);
		addon_loader.loadAddons();
		loading.setState("Init addons", 40);
		LoadingStage.INIT.proccess(addon_loader.addons);
		loading.setState("Load addons", 60);
		LoadingStage.LOAD.proccess(addon_loader.addons);
		loading.setState("Start Network", 70);
		self.updateIPAddresses();
		loading.addProgress(10);
		self.startNetworkServices();
		loading.addProgress(20);
		self.startDaemons();
		//Try to play tune
		tryAutorun();
		new TuneThread().start();
		//Main section
		System.out.println("Host name: " + hostname);
		System.out.println("LeJOS version: " + lejosversion);
		System.out.println("Menu version: " + menuversion);
		System.out.println("Initializing menu complete.");
		LocalEV3.get().getLED().setPattern(0);
		loading.stop();
		self.mainMenu();
		
		System.out.println("Cleaning up addons...");
		LoadingStage.CLEANUP.proccess(addon_loader.addons);
		self.stopMenu();
	}
	
	private static void init_menus()
	{
		MappedMenu.root = new MappedMenu(hostname, new String[]{"Run Default", "Programs", "Samples", "Tools", "Bluetooth", "Wifi", "PAN", "Sound", "System", "Version"}, new Icon[]{StockIcon.DEFAULT,StockIcon.PROGRAMS,StockIcon.SAMPLES,StockIcon.TOOLS,StockIcon.BLUETOOTH,StockIcon.WIFI,StockIcon.NETWORK,StockIcon.SOUND,StockIcon.EV3BRICK,StockIcon.LEJOS});
		MappedMenu.system = new MappedMenu("System", new String[]{"Delete Programs", "Auto Run", "Change Name", "NTP", "Suspend Menu", "Close IO", "Unset Default"}, new Icon[]{StockIcon.FORMAT,StockIcon.AUTORUN,StockIcon.EDIT,StockIcon.JAVA,StockIcon.SLEEP,StockIcon.NO,StockIcon.JAVA});
		MappedMenu.sound = new MappedMenu("Sound", new String[]{"", "", "", ""}, new Icon[]{StockIcon.SOUND, StockIcon.SOUND, StockIcon.SOUND, StockIcon.SOUND});
		MappedMenu.bluetooth = new MappedMenu("Bluetooth", new String[]{"Search/Pair", "Devices", "Visibility", "Change PIN", "Information"}, new Icon[]{StockIcon.SEARCH, StockIcon.EV3BRICK, StockIcon.EYE, StockIcon.KEY, StockIcon.INFO});
		MappedMenu.file = new MappedMenu("File", new String[]{"View", "Delete"},new Icon[]{StockIcon.DIRECTORY, StockIcon.DELETE});
		MappedMenu.executable = new MappedMenu("JAR", new String[]{"Run", "Debug", "Set as Default", "Delete"},new Icon[]{StockIcon.JAVA, StockIcon.DEBUG, StockIcon.DEFAULT, StockIcon.DELETE});
		MappedMenu.bluetooth_dev = new MappedMenu("Bluetooth", new String[]{"Remove"}, new Icon[]{StockIcon.DELETE});
		MappedMenu.boot_menu = new MappedMenu("Boot", new String[]{"Shutdown", "Cancel"}, new Icon[]{StockIcon.POWER, StockIcon.NO});
		
		if(!Boolean.parseBoolean(MainMenu.addon_loader.props.getProperty("enabled", "true")))
		{
			MappedMenu.system.add(new SimpleMenuEntry("Enable AL", StockIcon.TOOLS){
				@Override
				public void run()
				{
					if(MenuUtils.askConfirm("Enable Addon Loader?"))
					{
						MainMenu.addon_loader.props.setProperty("enabled", "true");
						MainMenu.addon_loader.props.store("AddonLoader config");
						MainMenu.self.exit = 2;
					}
				}
			});
		}
	}
	
	private static void tryAutorun()
	{
		File file = getDefaultProgram();
		if(file != null)
		{
			String auto = Settings.getProperty(defaultProgramAutoRunProperty, "");			
			if(auto.equals("ON") && !Button.LEFT.isDown())
			{
				System.out.println("Auto executing default program " + file.getPath());
				try
				{
					JarFile jar = new JarFile(file);
					String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
					jar.close();
					exec(file, JAVA_RUN_CP + file.getPath() + " lejos.internal.ev3.EV3Wrapper " + mainClass, PROGRAMS_DIRECTORY);
				}
				catch(IOException e)
				{
					System.err.println("Exception running autorun program");
				}
			}
		}
	}
	
	/**
	 *  Start the background threads
	 */
	private void startDaemons()
	{
		ind.start();
		rcons.start();
		BrickFinder.startDiscoveryServer(panConfig.getCurrentMode() == PANConfig.MODE_APP);
		remoteMenuThread.start();
	}
	
	/**
	 * Display the main system menu.
	 * Allow the user to select File, Bluetooth, Sound, System operations.
	 */
	private void mainMenu()
	{
		//DEBUGME MainMenu
		MappedMenu menu = MappedMenu.root;
		int selection = 0;
		while(this.exit == 0)
		{
			newScreen(hostname);
			ind.setDisplayState(IND_FULL);
			selection = menu.open();
			ind.setDisplayState(IND_NORMAL);
			switch(selection)
			{
				case 0:
					mainRunDefault();
					break;
				case 1:
					filesMenu(new File(PROGRAMS_DIRECTORY), false);
					break;
				case 2:
					filesMenu(new File(SAMPLES_DIRECTORY), false);
					break;
				case 3:
					filesMenu(new File(TOOLS_DIRECTORY), true);
					break;
				case 4:
					bluetoothMenu();
					break;
				case 5:
					wifiMenu();
					break;
				case 6:
					panConfig.panMenu();
					break;
				case 7:
					soundMenu();
					break;
				case 8:
					systemMenu();
					break;
				case 9:
					displayVersion();
					break;
				case -1:
					bootMenu();
					break;
				default:
					menu.get(selection).run();
					break;
			}
		}
	}
	
	/**
	 * Present the Bluetooth menu to the user.
	 * @author Rewritten by Enginecrafter77
	 */
	private void bluetoothMenu()
	{		
		if(bt == null)
		{
			msg("BT is off");
			return;
		}
		
		MappedMenu menu = MappedMenu.bluetooth;
		int selection = 0;
		while(selection >= 0)
		{
			newScreen("Bluetooth");
			LCD.drawString("Visible: " + (bt.getVisibility() ? "ON" : "OFF"), 1, 2);
			selection = menu.open();
			switch(selection)
			{
				case 0:
					bluetoothSearch();
					break;
				case 1:
					bluetoothDevices();
					break;
				case 2:
					changeBTVisibility();
					break;
				case 3:
					bluetoothChangePIN();
					break;
				case 4:
					bluetoothInformation();
					break;
				case -1:
					break;
				default:
					menu.get(selection).run();
					break;
			}
		}
	}
	
	/**
	 * Changes Bluetooth visibility.
	 */
	public void changeBTVisibility()
	{
		try
		{
			bt.setVisibility(!bt.getVisibility());
		}
		catch(BluetoothException e)
		{
			e.printStackTrace();
		}
		ind.updateNow();
	}
 
	/**
	 * Allow the user to change the Bluetooth PIN.
	 */
	private void bluetoothChangePIN()
	{
		String pinStr = SystemSettings.getStringSetting(pinProperty, "1234");
		int len = pinStr.length();
		byte[] pin = new byte[len];
		for (int i = 0; i < len; i++)
		{
			pin[i] = (byte)pinStr.charAt(i);
		}
		
		int[] newpin = MenuUtils.enterPin("Enter EV3 pin");
		if(newpin != null)
		{			
			try
			{
				PrintStream out = new PrintStream(new FileOutputStream("/etc/bluetooth/btpin"));
				out.println(String.valueOf(newpin));
				out.close();
				waitScreen.begin("Restart BT");
				Runtime.getRuntime().exec(START_BLUETOOTH).waitFor();
				waitScreen.end();
			}
			catch(Exception e)
			{
				System.err.println("Failed to set new bluetooth pin: ");
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * Performs the Bluetooth search operation.
	 * Displays menu with the found devices, so user can pair the BT
	 * device with the EV3.
	 */
	private void bluetoothSearch()
	{
		newScreen();
		Collection<RemoteBTDevice> c = bt.search();
		if(c.size() == 0)
		{
			msg("No device found");
			return;
		}
		RemoteBTDevice[] devices = new RemoteBTDevice[c.size()];
		String[] entries = new String[c.size()];
		Iterator<RemoteBTDevice> itr = c.iterator();
		for(int i = 0; itr.hasNext(); i++)
		{
			devices[i] = itr.next();
			entries[i] = devices[i].getName();
		}
		
		TextMenu menu = new TextMenu(entries);
		int selection = 0;
		while(selection >= 0)
		{
			newScreen("Devices");
			selection = menu.select(selection);
			if(selection >= 0)
			{
				RemoteBTDevice btd = devices[selection];
				newScreen("Pairing");
				int[] pin = MenuUtils.enterPin("PIN for " + btd.getName());
				if(pin == null)
				{
					selection = -1;
				}
				this.waitScreen.begin("Pairing\nwith\n" + btd.getName());
				boolean scf = true;
				try
				{
					Bluetooth.getLocalDevice().authenticate(btd.getAddress(), String.valueOf(pin));
				}
				catch (Exception e)
				{
					scf = false;
					e.printStackTrace();
				}
				this.waitScreen.end();
				msg(scf ? "Successful" : "Failed");
			}
		}
	}

	/**
	 * Display all currently known Bluetooth devices.
	 */
	private void bluetoothDevices()
	{
		newScreen();
		List<RemoteBTDevice> devList = (List<RemoteBTDevice>) Bluetooth.getLocalDevice().getPairedDevices();
		if(devList.size() <= 0)
		{
			msg("No known devices");
			return;
		}
		
		newScreen("Devices");
		String[] names = new String[devList.size()];
		for(int i = 0; i < devList.size(); i++)
		{
			names[i] = devList.get(i).getName();
		}

		TextMenu deviceMenu = new TextMenu(names);
		MappedMenu subMenu = MappedMenu.bluetooth_dev;
		int selection = 0;
		while(selection >= 0)
		{
			newScreen();
			selection = deviceMenu.select(selection);
			if(selection >= 0)
			{
				newScreen();
				RemoteBTDevice btrd = devList.get(selection);
				lcd.drawString(btrd.getName(), 2, 2);
				lcd.drawString(btrd.getAddress(), 0, 3);
				int subSelection = subMenu.open();
				if(subSelection == 0)
				{
					try
					{
						Bluetooth.getLocalDevice().removeDevice(btrd.getAddress());
						devList = (List<RemoteBTDevice>) Bluetooth.getLocalDevice().getPairedDevices();
						names = new String[devList.size()];
						for(int i = 0; i < devList.size(); i++)
						{
							names[i] = devList.get(i).getName();
						}
						deviceMenu.setItems(names);
					}
					catch (BluetoothException e)
					{
						e.printStackTrace();
					}
				}
				else if(subSelection >= 0)
				{
					subMenu.get(selection).run();
				}
			}
		}
	}
	
	/**
	 * Display Bluetooth information
	 */
	private void bluetoothInformation()
	{
		newScreen("Information");
		LocalBTDevice lbt = Bluetooth.getLocalDevice();
		LocalVersion ver = lbt.getLocalVersion();
		String v = ver.hci_ver >= bluetoothVersions.length ? "" : bluetoothVersions[ver.hci_ver];
		lcd.drawString("HCI " + v + " " + Integer.toHexString(ver.hci_ver) + "/" + Integer.toHexString(ver.hci_rev), 0, 2);
		v = ver.lmp_ver >= bluetoothVersions.length ? "n/a" : bluetoothVersions[ver.lmp_ver];
		lcd.drawString("LMP " + v + " " + Integer.toHexString(ver.lmp_ver) + "/" + Integer.toHexString(ver.lmp_subver), 0, 3);
		lcd.drawString("ID: " + lbt.getFriendlyName(), 0, 5);
		lcd.drawString(lbt.getBluetoothAddress(), 0, 6);
		getButtonPress();
	}
	
	/**
	 * Run the default program (if set).
	 */
	private void mainRunDefault()
	{
		File file = getDefaultProgram();
		if(file == null)
		{
	   		msg("No default set");
		}
		else
		{
			System.out.println("Executing default program " + file.getPath());
			try
			{
				JarFile jar = new JarFile(file);
				String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
				jar.close();
				exec(file, JAVA_RUN_CP + file.getPath() + " lejos.internal.ev3.EV3Wrapper " + mainClass, PROGRAMS_DIRECTORY);
			}
			catch(IOException e)
			{
				System.err.println("Exception running program");
			}
		}
	}
	
	/**
	 * Present the system menu.
	 * Allow the user to format the filesystem. Change timeouts and control
	 * the default program usage.
	 */
	private void systemMenu()
	{
		MappedMenu menu = MappedMenu.system;
		int selection = 0;
		while(selection >= 0)
		{
			this.newScreen("System");
			lcd.drawString("Battery: " + Battery.getVoltageMilliVolt() + "mV", 1, 1);
			lcd.drawString(MenuUtils.getFreeRam(), 1, 2);
			selection = menu.open();
			switch(selection)
			{
			case 0:
				if(MenuUtils.askConfirm("Delete all programs?"))
				{
					MenuUtils.removeContent("/home/lejos/programs");
				}
				break;
			case 1:
				systemAutoRun();
				break;
			case 2:
				String newName = InputMethod.current.call();
				if(newName != null)
				{
					setName(newName);
				}
				break;
			case 3:
				String host = InputMethod.current.call();
				if(host != null)
				{
					Settings.setProperty(ntpProperty, host);
				}
				break;
			case 4:
				System.out.println("Menu suspended.");
				this.suspend();
				Button.waitForAnyPress();
				this.resume();
				break;
			case 5:
				EV3IOPort.closeAll();
				break;
			case 6:
				if(Settings.getProperty(defaultProgramProperty, "").equals(""))
				{
					this.msg("No default set.");
				}
				else
				{
					Settings.setProperty(defaultProgramProperty, "");
					Settings.setProperty(defaultProgramAutoRunProperty, "");
					this.msg("Unset sucessfull");
				}
				break;
			case -1:
				break;
			default:
				menu.get(selection).run();
			}
		}
	}
	
	/**
	 * Present details of the default program
	 * Allow the user to specify run on system start etc.
	 */
	private void systemAutoRun()
	{
		File f = getDefaultProgram();
		if(f == null)
		{
	   		msg("No default set");
	   		return;
		}
		
		newScreen("Auto Run");
		lcd.drawString("Default Program:", 0, 2);
		lcd.drawString(f.getName(), 1, 3);
		
		Settings.setProperty(defaultProgramAutoRunProperty, MenuUtils.askConfirm("Run at power up?") ? "ON" : "OFF");
	}
	
	private void bootMenu()
	{
		MappedMenu menu = MappedMenu.boot_menu;
		this.newScreen("Boot");
		int selection = menu.open();
		switch(selection)
		{
		case 0:
			this.exit = 3;
			break;
		case 1:
			break;
		case -1:
			break;
		default:
			menu.get(selection).run();
		}
	}
	
	/**
	 * Get the default program as a file
	 */
	private static File getDefaultProgram()
	{
		String file = Settings.getProperty(defaultProgramProperty, "");
		if (file != null && file.length() > 0)
		{
			File f = new File(file);
			if (f.exists())
				return f;
			
		   	Settings.setProperty(defaultProgramProperty, "");
		   	Settings.setProperty(defaultProgramAutoRunProperty, "OFF");
		}
		return null;
	}

	/**
	 * Display the sound menu.
	 * Allow the user to change volume and key click volume.
	 * Completely rewritten from original LeJOS version.
	 * Beautiful :D
	 * @author Enginecrafter77
	 */
	private void soundMenu()
	{
		MappedMenu menu = MappedMenu.sound;
		String[] res = new String[menu.size()];
		Iterator<MenuEntry> itr = menu.iterator();
		for(int index = 0; itr.hasNext(); index++) res[index] = itr.next().getName();
		
		int selection = 0;
		int mv = Integer.parseInt(Settings.getProperty(Sound.VOL_SETTING, "0"));
		int kv = Integer.parseInt(Settings.getProperty(Button.VOL_SETTING, "0"));
		int kf = Integer.parseInt(Settings.getProperty(Button.FREQ_SETTING, "0"));
		int kl = Integer.parseInt(Settings.getProperty(Button.LEN_SETTING, "0"));
		while(selection >= 0)
		{
			newScreen("Sound");
			res[0] = "Master Volume: " + mv;
			res[1] = "Key Volume: " + kv;
			res[2] = "Freq: " + kf + "Hz";
			res[3] = "Length: " + kl + "ms";
			selection = menu.open();
			switch(selection)
			{
			case 0:
				mv = MenuUtils.cycleValue(mv, 0, 100, 10);
				Sound.playTone(500, 500, mv);
				Sound.setVolume(mv);
				break;
			case 1:
				kv = MenuUtils.cycleValue(kv, 0, 100, 10);
				Button.setKeyClickVolume(kv);
				break;
			case 2:
				kf = MenuUtils.cycleValue(kf, 100, 1000, 100);
				Button.setKeyClickTone(Key.ENTER, kf);
				break;
			case 3:
				kl = MenuUtils.cycleValue(kl, 50, 500, 50);
				Button.setKeyClickLength(kl);
				break;
			case -1:
				break;
			default:
				menu.get(selection).run();
			}
		}
		MainMenu.lcd.clear();
		this.waitScreen.begin("Saving");
		Settings.setProperty(Sound.VOL_SETTING, String.valueOf(mv));
		Settings.setProperty(Button.VOL_SETTING, String.valueOf(kv));
		Settings.setProperty(Button.FREQ_SETTING, String.valueOf(kf));
		Settings.setProperty(Button.LEN_SETTING, String.valueOf(kl));
		Delay.msDelay(500);
		this.waitScreen.end();
	}
	
	/**
	 * Display system version information.
	 */
	private void displayVersion()
	{
		if(HookRegistry.DISPLAY_VERISON.runHooks(0) > 0) return;
		HookRegistry.DISPLAY_VERISON.runHooks(1);
		newScreen("Version");
		lcd.drawString("leJOS:", 0, 2);
		lcd.drawString(lejosversion, 6, 2);
		lcd.drawString("Menu:", 0, 3);
		lcd.drawString(menuversion, 6, 3);
		getButtonPress();
		HookRegistry.DISPLAY_VERISON.runHooks(2);
	}
	
	/**
	 * Read a button press.
	 * If the read timesout then exit the system.
	 * @return The bitcode of the button.
	 */
	protected int getButtonPress()
	{
		long timeoutCnt = (timeout == 0 ? Long.MAX_VALUE : (timeout*60000)/200);
		while (timeoutCnt-- > 0)
		{	
			int value = Button.waitForAnyPress(200);
			if (value != 0) return value;
			if (suspend)
				waitResume();
		}
		shutdown();
		return 0;
	}
	
	/**
	 * Present the menu for a single file.
	 * Rewritten from original LeJOS fileMenu.
	 * @param file
	 * @author Enginecrafter77
	 */
	private void fileMenu(File file, boolean tools)
	{
		MappedMenu menu = MappedMenu.file;
		String extension = Utils.getExtension(file.getName());
		newScreen(file.getName());
		LCD.drawString("Size: " + DataSize.formatDataSize(file.length(), DataSize.BYTE), 1, 2);
		if(extension.equals("jar"))
		{
			menu = MappedMenu.executable;
			executableMenu(file, tools, menu);
			return;
		}
		
		int selection = menu.open();
		switch(selection)
		{
		case 0:
			if(file.isDirectory())
			{
				filesMenu(file, tools);
			}
			else if(extension.equals("wav"))
			{
				Sound.playSample(file);
			}
			else
			{
				try
				{
					default_viewer.open(new FileReader(file.getPath()));
				}
				catch(IOException e)
				{
					System.err.println("Exception loading file");
				}
			}
			break;
		case 1:
			file.delete();
			break;
		case -1:
			break;
		default:
			menu.get(selection).run();
			break;
		}
	}
	
	/**
	 * Used to display menu for executable files.
	 * @param file
	 * @author Enginecrafter77
	 */
	private void executableMenu(File file, boolean isTool, MappedMenu menu)
	{
		String directory = file.getParent();
		int selection = menu.open();
		switch(selection)
		{
		case 0:
			System.out.println("Running program: " + file.getPath());
			if(isTool)
			{
				if(HookRegistry.RUN_TOOL.runHooks(0) > 0) return;
				HookRegistry.RUN_TOOL.runHooks(1);
				execInThisJVM(file);
				HookRegistry.RUN_TOOL.runHooks(2);
			}
			else
			{
				if(HookRegistry.RUN_PROG.runHooks(0) > 0) return;
				HookRegistry.RUN_PROG.runHooks(1);
				try
				{
					JarFile jar = new JarFile(file);
					String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
					jar.close();
					exec(file, JAVA_RUN_CP + file.getPath() + " lejos.internal.ev3.EV3Wrapper " + mainClass, directory);
				}
				catch(IOException e)
				{
					System.err.println("Exception running program");
				}
				HookRegistry.RUN_PROG.runHooks(2);
			}
			break;
		case 1:
			System.out.println("Debugging program: " + file.getPath());
			JarFile jar = null;
			try
			{
				jar = new JarFile(file);
				String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
				jar.close();
				exec(file, JAVA_DEBUG_CP + file.getPath() + " lejos.internal.ev3.EV3Wrapper " + mainClass, directory);
			}
			catch(IOException e)
			{
				System.err.println("Exception running program");
			}
			break;
		case 2:
			Settings.setProperty(defaultProgramProperty, file.getAbsolutePath());
			break;
		case 3:
			file.delete();
			break;
		case -1:
			break;
		default:
			menu.get(selection).run();
			break;
		}
	}
	
	/**
	 * Execute a program and display its output to System.out and error stream to System.err
	 */
	private static void exec(File jar, String command, String directory)
	{
		self.suspend();
		try
		{
			if(jar != null)
			{
				String jarName = jar.getName();
				programName = jarName.substring(0, jarName.length() - 4);
			}
			
			WaitScreen.drawLaunchScreen();
			
			program = new ProcessBuilder(command.split(" ")).directory(new File(directory)).start();
			BufferedReader input = new BufferedReader(new InputStreamReader(program.getInputStream()));
			BufferedReader err= new BufferedReader(new InputStreamReader(program.getErrorStream()));
			
			echoIn = new EchoThread(jar.getPath().replace(".jar", ".out"), input, System.out);
			echoErr = new EchoThread(jar.getPath().replace(".jar", ".err"), err, System.err);
			
			echoIn.start(); 
			echoErr.start();
			
			System.out.println("Executing " + command + " in " + directory);
			
			while(true)
			{
				int b = Button.getButtons(); 
				if(b == 6)
				{
					System.out.println("Killing the process");
					program.destroy(); 
					break;
				}
				if(!echoIn.isAlive() && !echoErr.isAlive())
				{
					break;           
				}
				Delay.msDelay(200);
			}
			program.waitFor();
			System.out.println("Program finished");
		}
		catch (Exception e)
		{
			System.err.println("Failed to execute program: " + e);
		}
		finally
		{
			Button.LEDPattern(0);
			program = null;
			self.resume();
		}
	}
	
	/**
	 * Execute a program and display its output to System.out and error stream to System.err
	 */
	private static void startProgram(String command, File jar)
	{
		if(HookRegistry.RUN_PROG.runHooks(0) > 0) return;
		HookRegistry.RUN_PROG.runHooks(1);
		try
		{
			if(program != null) return;
			String[] args = command.split(" ");
			File directory = jar.getParentFile();
			
			programName = jar.getName().replace(".jar", "");
			
			program = new ProcessBuilder(args).directory(directory).start();
			echoIn = new EchoThread(jar.getPath().replace(".jar",".out"), new BufferedReader(new InputStreamReader(program.getInputStream())), System.out);
			echoErr = new EchoThread(jar.getPath().replace(".jar",".err"), new BufferedReader(new InputStreamReader(program.getErrorStream())), System.err);
			
			echoIn.start();
			echoErr.start();
			self.suspend();
			WaitScreen.drawLaunchScreen();		  
			
			System.out.println("Executing " + command + " in " + directory);
		}
		catch(Exception e)
		{
			System.err.println("Failed to start program: " + e);
			e.printStackTrace();
			self.resume();
		}
		HookRegistry.RUN_PROG.runHooks(2);
	}
	
	/**
	 * Display the files in the file system.
	 * Allow the user to choose a file for further operations.
	 * Rewritten by Enginecrafter77
	 */
	private void filesMenu(File dir, boolean tools)
	{
		TextMenu menu = new TextMenu(null);
		File[] files = dir.listFiles();;
		menu.setItems(Utils.filesToString(files));
		
		int selection = 0;
		while(selection >= 0)
		{
			newScreen("Files");
			selection = menu.select(selection);
			if(selection >= 0)
			{
				fileMenu(files[selection], tools);
				files = dir.listFiles();
				menu.setItems(Utils.filesToString(files));
			}
		}
	}
	
	/**
	 * Start a new screen display using the current title.
	 */
	public void newScreen()
	{
		lcd.clear();
		ind.updateNow();
	}
	
	/**
	 * Start a new screen display.
	 * Clear the screen and set the screen title.
	 * @param title
	 */
	public void newScreen(String title)
	{
		indiBA.setTitle(title);
		newScreen();
	}
	
	/**
	 * Display a status message
	 * @param msg
	 */
	public void msg(String msg)
	{
		newScreen();
		lcd.drawString(msg, 0, 2);
		long start = System.currentTimeMillis();
		int button;
		int buttons = Button.readButtons();
		do
		{
			Thread.yield();
			
			int buttons2 = Button.readButtons();
			button = buttons2 & ~buttons;
		} while (button != Button.ID_ESCAPE && System.currentTimeMillis() - start < 2000);
	}
	
	/**
	 * Get all the IP addresses for the device, return true if either the wlan or
	 * pan address has changed.
	 */
	public synchronized boolean updateIPAddresses()
	{
		List<String> result = new ArrayList<String>();
		Enumeration<NetworkInterface> interfaces;
		String oldWlan = wlanAddress;
		String oldPan = panAddress;
		wlanAddress = null;
		panAddress = null;
		ips.clear();
		try
		{
			interfaces = NetworkInterface.getNetworkInterfaces();
		}
		catch(SocketException e)
		{
			System.err.println("Failed to get network interfaces: " + e);
			return false;
		}
		while(interfaces.hasMoreElements())
		{
			NetworkInterface current = interfaces.nextElement();
			try
			{
				if (!current.isUp() || current.isLoopback() || current.isVirtual()) continue;
			}
			catch (SocketException e)
			{
				System.err.println("Failed to get network properties: " + e);
			}
			Enumeration<InetAddress> addresses = current.getInetAddresses();
			while(addresses.hasMoreElements())
			{
				InetAddress current_addr = addresses.nextElement();
				if(current_addr.isLoopbackAddress()) continue;
				result.add(current_addr.getHostAddress());
				if(current.getName().equals(WLAN_INTERFACE)) wlanAddress = current_addr.getHostAddress();
				else if(current.getName().equals(PAN_INTERFACE)) panAddress = current_addr.getHostAddress();
			}
		}
		ips = result;
		// have any of the important addresses changed?
		return !(oldWlan == wlanAddress || (oldWlan != null && wlanAddress != null && wlanAddress.equals(oldWlan))) || !(oldPan == panAddress || (oldPan != null && panAddress != null && panAddress.equals(oldPan)));
	}
	
	private void wifiMenu()
	{
		System.out.println("Finding access points ...");
		LocalWifiDevice wifi = Wifi.getLocalDevice("wlan0");
		String[] names;
		try
		{
			names = wifi.getAccessPointNames();
		}
		catch(Exception e)
		{
			System.err.println("Exception getting access points: " + e);
			msg("No Access Points found");
			return;
		}
		for(int i = 0; i < names.length; i++)
		{
			if(names[i].isEmpty())
			{
				names[i] = "[HIDDEN]";
			}
		}
		TextMenu menu = new TextMenu(names);
		
		int selection = 0;
		newScreen("WiFi");
		selection = menu.select(selection);
		if(selection >= 0)
		{
			NetUtils.connectAP(names[selection]);
		 	selection = -1;
		}
	}
	
	public void startNetworkServices()
	{
		System.out.println("Starting RMI");
		String rmiIP = (wlanAddress != null ? wlanAddress : (panAddress != null ? panAddress : "127.0.0.1"));
		System.out.println("Setting java.rmi.server.hostname to " + rmiIP);
		System.setProperty("java.rmi.server.hostname", rmiIP);
		
		try
		{
			LocateRegistry.createRegistry(1099); 
			System.out.println("java RMI registry created.");
			RMIRemoteEV3 ev3 = new RMIRemoteEV3();
			Naming.rebind("//localhost/RemoteEV3", ev3);
			RMIRemoteMenu remoteMenu = new RMIRemoteMenu(self);
			Naming.rebind("//localhost/RemoteMenu", remoteMenu);
			
			String dt = SNTPClient.getDate(Settings.getProperty(ntpProperty, "1.uk.pool.ntp.org"));
			System.out.println("Date and time is " + dt);
			Runtime.getRuntime().exec("date -s " + dt);
		}
		catch (Exception e)
		{
			System.err.println("RMI failed to start: " + e);
		}
	}
	
	private void execInThisJVM(File jar)
	{
		suspend();
		try
		{
			LCD.clearDisplay();
			JarMain m = new JarMain(jar);
			m.run();
			m.close();
		}
		catch(Exception e)
		{
			toolException(e);
			System.err.println("Exception in execution of tool: " + e);
			e.printStackTrace();
		}
		finally
		{
			resume();
		}
	}
	
	private void exit_command(String message, String cmdline)
	{
		try
		{
			this.suspend();
			ProcessBuilder proc = new ProcessBuilder(cmdline).inheritIO();
			lcd.drawString(message, 2, 6);
			lcd.refresh();
			proc.start();
			System.exit(0);
		}
		catch(Exception e)
		{
			System.err.println("Error when restarting menu");
			e.printStackTrace();
		}
	}
	
	private void stopMenu()
	{
		System.out.println("Menu finished");
		System.gc();
		
		switch(this.exit)
		{
		case 1:
			System.exit(0);
			break;
		case 2:
			this.exit_command("Restarting", "/home/root/lejos/bin/startmenu");
			break;
		case 3:
			this.exit_command("Shutting down", "init 0");
			break;
		case 4:
			this.exit_command("Reboot", "init 6");
			break;
		default:
			break;
		}
	}
	
	//						//
	//	UNTAMPERED SECTION 	//
	//						//
	
	/**
	 * If the menu is suspended wait for it to be resumed. Handle any program exit
	 * while we wait.
	 */
	public void waitResume()
	{
		while (suspend) {
			if (program != null && !echoIn.isAlive() && !echoErr.isAlive()) {
				stopProgram();
				break;
			}
			int b = Button.getButtons(); 
			if (b == 6) {
				if (program != null)
					stopProgram();
				else
					// should we do this?
					resume();
				break;
			}
			Delay.msDelay(200);
		}		
	}
	
	protected void startNetwork(String startup, boolean startServices) {
		try {
			Process p = Runtime.getRuntime().exec(startup);
			BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String statusMsg;
			while((statusMsg = input.readLine()) != null)
			{
				waitScreen.status(statusMsg);
			}
			int status = p.waitFor();
			System.out.println("start returned " + status);
			updateIPAddresses();
			Delay.msDelay(2000);
			if (startServices)
			{
				waitScreen.status("Start services");
				startNetworkServices();
				Delay.msDelay(2000);
			}
		} catch (Exception e) {
			System.err.println("Failed to execute: " + startup + " : " + e);
			e.printStackTrace();
		
		}
	}
	
	private void toolException(Throwable t) {
		Sound.buzz();
		TextLCD lcd = BrickFinder.getDefault().getTextLCD(Font.getSmallFont());
		int offset = 0;
		// Get rid of invocation exception
		if (t.getCause() != null) t = t.getCause();
		while (true)
		{
			lcd.clear();
			lcd.drawString("Tool exception:", offset, 1);
			lcd.drawString(t.getClass().getName(), offset, 3);
			if (t.getMessage() != null) lcd.drawString(t.getMessage(), offset, 4);		
			
			if (t.getCause() != null) {
				lcd.drawString("Caused by:", offset, 5);
				lcd.drawString(t.getCause().toString(), offset, 6);
			}
			
			StackTraceElement[] trace = t.getStackTrace();
			for(int i=0;i<7 && i < trace.length ;i++) lcd.drawString(trace[i].toString(), offset, 8+i);
			
			lcd.refresh();
			int id = Button.waitForAnyEvent();
			if (id == Button.ID_ESCAPE) break;
			if (id == Button.ID_LEFT) offset += 5;
			if (id == Button.ID_RIGHT)offset -= 5;
			if (offset > 0) offset = 0;
		}
		lcd.clear();
	}
	
	public static void resetMotors() {
		for(String portName: new String[]{"A","B","C","D"}) {
			Port p = LocalEV3.get().getPort(portName);
			TachoMotorPort mp = p.open(TachoMotorPort.class);
			mp.controlMotor(0, TachoMotorPort.FLOAT);
			mp.resetTachoCount();
			mp.close();
		}
	}
	
	//						//
	//	OVERRIDE SECTION 	//
	//						//
	
	@Override
	public void stopProgram()
	{		   
		try
		{  
			if(program == null) return;
			program.destroy();
			program.waitFor();
			System.out.println("Program finished");
		}
		catch(Exception e)
		{
			System.err.println("Failed to stop program: " + e);
		}
		finally
		{
			Button.LEDPattern(0);
			program = null;
			self.resume();			  
		}
	}
	
	@Override
	public String getExecutingProgramName()
	{
		if (program == null) return null;
		return programName;
	}
	
	@Override
	public void runProgram(String programName)
	{
		JarFile jar = null;
		String fullName = PROGRAMS_DIRECTORY + "/" + programName + ".jar";
		try {
			File jarFile = new File(fullName);
			jar = new JarFile(jarFile);
			String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
			jar.close();
			startProgram(JAVA_RUN_CP + fullName + " lejos.internal.ev3.EV3Wrapper " + mainClass, jarFile);
		} catch (IOException e) {
			System.err.println("Failed to run program");
		}
	}

	@Override
	public boolean deleteFile(String fileName) {
		File f = new File(fileName);
		return f.delete();
	}

	@Override
	public String[] getProgramNames() {
		File[] files = (new File(PROGRAMS_DIRECTORY)).listFiles();
		String[] fileNames = new String[files.length];
		for(int i=0;i<files.length;i++) {
			fileNames[i] = files[i].getName();
		}
		return fileNames;
	}

	@Override
	public void runSample(String programName) {
		JarFile jar = null;
		String fullName = SAMPLES_DIRECTORY + "/" + programName + ".jar";
		try {
			File jarFile = new File(fullName);
			jar = new JarFile(jarFile);
			String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
			jar.close();
			startProgram(JAVA_RUN_CP + fullName + " lejos.internal.ev3.EV3Wrapper " + mainClass, jarFile);
		} catch (IOException e) {
			System.err.println("Failed to run program");
		}
	}

	@Override
	public void debugProgram(String programName) {
		JarFile jar = null;
		String fullName = PROGRAMS_DIRECTORY + "/" + programName + ".jar";
		try {
			File jarFile = new File(fullName);
			jar = new JarFile(jarFile);
			String mainClass = jar.getManifest().getMainAttributes().getValue("Main-class");
			jar.close();
			startProgram(JAVA_DEBUG_CP + fullName + " lejos.internal.ev3.EV3Wrapper " + mainClass, jarFile);
		} catch (IOException e) {
			System.err.println("Failed to run program");
		}
	}

	@Override
	public String[] getSampleNames() {
		File[] files = (new File(SAMPLES_DIRECTORY)).listFiles();
		String[] fileNames = new String[files.length];
		for(int i=0;i<files.length;i++) {
			fileNames[i] = files[i].getName();
		}
		return fileNames;
	}

	@Override
	public long getFileSize(String filename) {
		return new File(filename).length();
	}

	@Override
	public boolean uploadFile(String fileName, byte[] contents) {
		try {
			FileOutputStream out = new FileOutputStream(fileName);
			out.write(contents);
			out.close();
			return true;
		} catch (IOException e) {
			System.out.println("Failed to upload file: " + e);
			return false;
		}
	
	}
	
	@Override
	public byte[] fetchFile(String fileName) {
		File f = new File(fileName);
		FileInputStream in;
		try {
			in = new FileInputStream(f);
			byte[] data = new byte[(int)f.length()];
			in.read(data);
			in.close();
			return data;
		} catch (IOException e) {
			System.err.println("Failed to fetch file: " + e);
			return null;
		}

	}

	@Override
	public String getSetting(String setting) {
		return Settings.getProperty(setting, null);
	}

	@Override
	public void setSetting(String setting, String value) {
		Settings.setProperty(setting, value);
	}

	@Override
	public void deleteAllPrograms() {
		File dir = new File(PROGRAMS_DIRECTORY);
		for (String fn : dir.list()) {
			File aFile = new File(dir,fn);
			System.out.println("Deleting " + aFile.getPath());
			aFile.delete();
		}
	}

	@Override
	public String getVersion() {
		return lejosversion;
	}

	@Override
	public String getMenuVersion() {
		return menuversion;
	}

	@Override
	public String getName() {
		return hostname;
	}

	@Override
	public void setName(String name) {
		waitScreen.begin("Change\nSystem\nName");
		hostname = name;
		waitScreen.status("Save new name");
		// Write host to /etc/hostname
		try {
			PrintStream out = new PrintStream(new FileOutputStream("/etc/hostname"));
			out.println(name);
			out.close();
		} catch (FileNotFoundException e) {
			System.err.println("Failed to write to /etc/hostname: " + e);
		}
		
		try {
			Process p = Runtime.getRuntime().exec("hostname " + hostname);
			int status = p.waitFor();
			System.out.println("hostname returned " + status);
		} catch (Exception e) {
			System.err.println("Failed to execute hostname: " + e);
		}
		
		startNetwork(START_WLAN, false);
		startNetwork(START_PAN, true);
		waitScreen.end();
	}
	
	@Override
	public void suspend() {
		suspend = true;
		ind.suspend();
		lcd.clear();
		LCD.setAutoRefresh(false);
		lcd.refresh();
	}

	@Override
	public void resume() {
		lcd.clear();
		lcd.refresh();
		LCD.setAutoRefresh(true);
		ind.resume();
		suspend = false;
	}
	
	@Override
	public void shutdown()
	{
		this.exit = 3; //Set the exit state so the addons can be safely cleaned.
	}
	
	//				//
	//	DEPRECATED	//
	//				//

	@Deprecated
	public boolean isSuspended()
	{
		return MainMenu.suspend;
	}
	
	@Deprecated
	public void setCurrentMenu(GraphicMenu menu) {}
	
	@Deprecated
	protected String getRunningName()
	{
		return programName;
	}
	
}
