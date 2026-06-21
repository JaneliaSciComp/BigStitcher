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
package net.preibisch.stitcher.algorithm.lucaskanade;

import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineGet;

public interface WarpFunction
{
	/**
	 * @return the number of parameters of the warp function.
	 */
	public int numParameters();

	/**
	 * Compute the partial derivative of the <em>d</em>th dimension of the
	 * warped image coordinate by the <em>param</em>th parameter of the warp
	 * function. The partial derivative is evaluated at at the given coordinates
	 * and the parameter vector <em>p=0</em>.
	 *
	 * @param pos
	 *            coordinates at which to evaluate the derivative.
	 * @param d
	 *            dimension of the warped image coordinate whose derivative to
	 *            take.
	 * @param param
	 *            parameter by which to derive.
	 * @return the partial derivative
	 */
	public double partial( RealLocalizable pos, int d, int param );

	/**
	 * get affine transform corresponding to a given parameter vector.
	 *
	 * @param p parameter vector
	 * @return the transform
	 */
	public AffineGet getAffine( double[] p );
}
