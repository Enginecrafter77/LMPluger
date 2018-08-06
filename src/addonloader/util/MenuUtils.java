package addonloader.util;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import addonloader.menu.MappedMenu;
import addonloader.menu.MenuEntry;
import addonloader.menu.SubmenuEntry;
import addonloader.util.ui.Icon;
import addonloader.util.ui.StockIcon;
import addonloader.util.xml.XElement;
import lejos.hardware.lcd.LCD;

/**
 * Class used to store random static functions used wherever in the code to keep the code clean.
 * @author Enginecrafter77
 */
public class MenuUtils {
	
	/**
	 * Basically, it adds number until it reaches it's roof, then it starts over.
	 * Mathematically: Appends <b>diff</b> to <b>num</b> until <b>num</b> is equal to <b>max</b>. Then sets <b>num</b> to <b>min</b>.
	 * @param num
	 * @param min
	 * @param max
	 * @param diff
	 * @return
	 */
	public static int cycleValue(int num, int min, int max, int diff)
	{
		int counted = num + diff;
		if(counted > max)
		{
			counted = min;
		}
		return counted;
	}
	
	/**
	 * Removes all files from directory with little more care on IO bandwidth.
	 * @param ct The directory to empty.
	 */
	public static void removeContent(String ct)
	{
		File[] files = new File(ct).listFiles();
		for(File f : files)
		{
			f.delete();
		}
	}
	
	public static String getFreeRam()
	{
		return "Free RAM: " + DataSize.formatDataSize(Runtime.getRuntime().freeMemory(), DataSize.BYTE);
	}
	
	/**
	 * Adds or subtracts value until point is reached, then set it to opposite value.
	 * @param num Working subject
	 * @param max Maximal number
	 * @param min Minimal number
	 * @param forward Add? Then true.
	 * @return Modified num value.
	 */
	public static int moveNumber(int num, int max, int min, boolean forward)
	{
		if(forward)
		{
			if(num < max) num++;
			else num = min;
		}
		else
		{
			if(num > min) num--;
			else num = max;
		}
		return num;
	}
	
	/**
	 * Ask the user for confirmation of an action.
	 * @param prompt A description of the action about to be performed
	 * @param def A default selection.
	 * @return True if pressed yes.
	 */
	public static boolean askConfirm(String prompt, boolean def)
	{
		MappedMenu menu = new MappedMenu("Confirm", new String[]{"No", "Yes"}, new Icon[]{StockIcon.NO, StockIcon.YES});
		LCD.drawString(prompt, 1, 2);
		return menu.open(def ? 1 : 0) == 1;
	}
	
	public void generateFromXML(MappedMenu parent, XElement source) throws NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException
	{
		if(source.name.equals("menu"))
		{
			SubmenuEntry current = new SubmenuEntry(source.getValue("name"), StockIcon.valueOf(source.getValue("icon")));
			parent.add(current);
			Iterator<XElement> itr = source.children.iterator();
			while(itr.hasNext()) generateFromXML(current, itr.next());
		}
		else
		{
			Class<?> cls = MenuUtils.class.getClassLoader().loadClass(source.getValue("class"));
			if(!MenuEntry.class.isAssignableFrom(cls)) throw new ClassNotFoundException("Provided class " + cls.getCanonicalName() + " does not extend MenuEntry");
			parent.add((MenuEntry)cls.newInstance());
		}
	}
	
	/**
	 * Prints formatted version number. <br>
	 * <b>Examples:</b>
	 * <p>num: 65, places: 2 = 6.5</p>
	 * <p>num: 73, places: 3 = 0.7.3</p>
	 * <p>num: 239, places: 2 = 23.9</p>
	 * @param num The number of the version to display.
	 * @param places Places of the displayed version, common are 2 and 3.
	 * @return Formatted version string.
	 */
	public static String formatVersion(int num, int places)
	{
		StringBuilder str = new StringBuilder();
		str.append(String.valueOf(num));
		while(str.length() < places) str.insert(0, '0'); //Pad with zeroes if the length is insufficient
		for(int i = str.length() - 1; i >= 0; i--) //Insert the point marks
		{
			//If there are remaining places, and if we are not on the beginning of line
			if(places > 1 && i > 0)
			{
				str.insert(i, '.');
				places--;
			}
		}
		return str.toString();
	}
	
	/**
	 * Remaps number x with in bounds to out bounds.
	 * @param x
	 * @param in_min
	 * @param in_max
	 * @param out_min
	 * @param out_max
	 * @return
	 */
	public static int map(int x, int in_min, int in_max, int out_min, int out_max)
	{
		return (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
	}
	
}
