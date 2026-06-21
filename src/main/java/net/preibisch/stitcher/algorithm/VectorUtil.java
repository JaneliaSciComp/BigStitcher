/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2026 Big Stitcher developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.stitcher.algorithm;

public class VectorUtil
{
	public static double getVectorLength(double[] vec){
		double res = 0;
		for (int i = 0; i<vec.length; i++)
			res += vec[i] * vec[i];
		return Math.sqrt( res );
	}
	
	public static double[] getVectorDiff(double[] a, double[] b)
	{
		double[] res = new double[a.length];
		for (int i = 0; i<a.length; i++)
			res[i] = b[i] - a[i];
		return res;
	}
	
	public static double[] getVectorSum(double[] a, double[] b)
	{
		double[] res = new double[a.length];
		for (int i = 0; i<a.length; i++)
			res[i] = b[i] + a[i];
		return res;
	}

}
