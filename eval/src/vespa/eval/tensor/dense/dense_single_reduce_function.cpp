// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "dense_single_reduce_function.h"
#include "dense_tensor_view.h"
#include <vespa/eval/eval/value.h>

namespace vespalib::tensor {

using eval::Aggr;
using eval::InterpretedFunction;
using eval::TensorEngine;
using eval::TensorFunction;
using eval::Value;
using eval::ValueType;
using eval::as;

using namespace eval::tensor_function;
using namespace eval::aggr;

namespace {

struct Params {
    const ValueType &result_type;
    size_t outer_size;
    size_t dim_size;
    size_t inner_size;
    Params(const ValueType &result_type_in, const ValueType &child_type, size_t dim_idx)
        : result_type(result_type_in), outer_size(1), dim_size(1), inner_size(1)
    {
        for (size_t i = 0; i < child_type.dimensions().size(); ++i) {
            if (i < dim_idx) {
                outer_size *= child_type.dimensions()[i].size;
            } else if (i == dim_idx) {
                dim_size *= child_type.dimensions()[i].size;
            } else {
                inner_size *= child_type.dimensions()[i].size;
            }
        }
    }
};

template <typename CT, typename AGGR>
CT reduce_cells(const CT *src, size_t dim_size, size_t stride, AGGR &aggr) {
    aggr.first(*src);
    for (size_t i = 1; i < dim_size; ++i) {
        src += stride;
        aggr.next(*src);
    }
    return aggr.result();
}

template <typename CT, typename AGGR>
void my_single_reduce_op(InterpretedFunction::State &state, uint64_t param) {
    const auto &params = *(const Params *)(param);
    const CT *src = DenseTensorView::typify_cells<CT>(state.peek(0)).cbegin();
    auto dst_cells = state.stash.create_array<CT>(params.outer_size * params.inner_size);
    AGGR aggr;
    CT *dst = dst_cells.begin();
    const size_t block_size = (params.dim_size * params.inner_size);
    for (size_t outer = 0; outer < params.outer_size; ++outer) {
        for (size_t inner = 0; inner < params.inner_size; ++inner) {
            *dst++ = reduce_cells<CT, AGGR>(src + inner, params.dim_size, params.inner_size, aggr);
        }
        src += block_size;
    }
    state.pop_push(state.stash.create<DenseTensorView>(params.result_type, TypedCells(dst_cells)));
}

template <typename CT>
InterpretedFunction::op_function my_select_2(Aggr aggr) {
    switch (aggr) {
    case Aggr::AVG:   return my_single_reduce_op<CT, Avg<CT>>;
    case Aggr::COUNT: return my_single_reduce_op<CT, Count<CT>>;
    case Aggr::PROD:  return my_single_reduce_op<CT, Prod<CT>>;
    case Aggr::SUM:   return my_single_reduce_op<CT, Sum<CT>>;
    case Aggr::MAX:   return my_single_reduce_op<CT, Max<CT>>;
    case Aggr::MIN:   return my_single_reduce_op<CT, Min<CT>>;
    }
    abort();
}

InterpretedFunction::op_function my_select(CellType cell_type, Aggr aggr) {
    if (cell_type == ValueType::CellType::DOUBLE) {
        return my_select_2<double>(aggr);
    }
    if (cell_type == ValueType::CellType::FLOAT) {
        return my_select_2<float>(aggr);
    }
    abort();
}

bool check_input_type(const ValueType &type) {
    return (type.is_dense() && ((type.cell_type() == CellType::FLOAT) || (type.cell_type() == CellType::DOUBLE)));
}

} // namespace vespalib::tensor::<unnamed>

DenseSingleReduceFunction::DenseSingleReduceFunction(const ValueType &result_type,
                                                     const TensorFunction &child,
                                                     size_t dim_idx, Aggr aggr)
    : Op1(result_type, child),
      _dim_idx(dim_idx),
      _aggr(aggr)
{
}

DenseSingleReduceFunction::~DenseSingleReduceFunction() = default;

InterpretedFunction::Instruction
DenseSingleReduceFunction::compile_self(const TensorEngine &, Stash &stash) const
{
    auto op = my_select(result_type().cell_type(), _aggr);
    auto &params = stash.create<Params>(result_type(), child().result_type(), _dim_idx);
    static_assert(sizeof(uint64_t) == sizeof(&params));
    return InterpretedFunction::Instruction(op, (uint64_t)&params);
}

const TensorFunction &
DenseSingleReduceFunction::optimize(const TensorFunction &expr, Stash &stash)
{
    auto reduce = as<Reduce>(expr);
    if (reduce && (reduce->dimensions().size() == 1) &&
        check_input_type(reduce->child().result_type()) &&
        expr.result_type().is_dense())
    {
        size_t dim_idx = reduce->child().result_type().dimension_index(reduce->dimensions()[0]);
        assert(dim_idx != ValueType::Dimension::npos);
        assert(expr.result_type().cell_type() == reduce->child().result_type().cell_type());
        return stash.create<DenseSingleReduceFunction>(expr.result_type(), reduce->child(), dim_idx, reduce->aggr());
    }
    return expr;
}

} // namespace vespalib::tensor
