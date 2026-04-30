#pragma once

#include <functional>
#include <queue>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <future>
#include <stdexcept>

namespace aman {

// Fixed-size thread pool for concurrent image preprocessing
class ThreadPool {
public:
    explicit ThreadPool(size_t num_threads);
    ~ThreadPool();

    // Submit a callable; returns a future for the result
    template<typename F, typename... Args>
    auto submit(F&& f, Args&&... args)
        -> std::future<std::invoke_result_t<F, Args...>>
    {
        using ReturnType = std::invoke_result_t<F, Args...>;

        auto task = std::make_shared<std::packaged_task<ReturnType()>>(
            std::bind(std::forward<F>(f), std::forward<Args>(args)...)
        );

        std::future<ReturnType> result = task->get_future();
        {
            std::unique_lock<std::mutex> lock(queue_mutex_);
            if (stop_) throw std::runtime_error("submit on stopped ThreadPool");
            tasks_.emplace([task]() { (*task)(); });
        }
        condition_.notify_one();
        return result;
    }

    size_t size() const { return workers_.size(); }

private:
    std::vector<std::thread>          workers_;
    std::queue<std::function<void()>> tasks_;
    std::mutex                        queue_mutex_;
    std::condition_variable           condition_;
    bool                              stop_ = false;
};

} // namespace aman
