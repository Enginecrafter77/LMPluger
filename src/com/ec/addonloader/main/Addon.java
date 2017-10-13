package com.ec.addonloader.main;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Anotation used on classes that describe an addon for menu.
 * Classes must extends {@link MenuAddon}
 * @author Enginecrafter77
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Addon {
	/**
	 * @return The name of the addon that will be loaded to the MenuAddon instance.
	 */
	String name();
}